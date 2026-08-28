package org.dromara.db.connector.redis;

import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.spi.QueryParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis 命令解析器（REDIS-101 / docs/06 §8）。
 *
 * <p>Redis 不使用 SQL AST——命令为 RESP 结构化参数。本解析器将命令文本按 RESP 规则分词
 * （尊重双引号），分类动作并提取 Key 前缀资源路径，<b>不接受原始文本拼接</b>（docs/06 §8.1：
 * 执行器只以结构化参数派发，杜绝命令注入）。</p>
 *
 * <p><b>P0 白名单</b>（docs/06 §8.2）：SCAN/TYPE/EXISTS/TTL/PTTL/GET/MGET/HGET/HMGET/HGETALL/
 * HSCAN/HLEN/LRANGE/LLEN/SMEMBERS/SSCAN/SCARD/SISMEMBER/ZRANGE/ZREVRANGE/ZRANGEBYSCORE/ZSCAN/
 * ZCARD/ZSCORE/XRANGE/XREVRANGE/XLEN。归 REDIS_SCAN/REDIS_READ，readonly=true。</p>
 *
 * <p><b>无条件拒绝</b>（docs/06 §8.3）：KEYS/MONITOR/CONFIG/DEBUG/SHUTDOWN/EVAL/EVALSHA/FUNCTION/
 * SCRIPT/FLUSHDB/FLUSHALL/MIGRATE/RESTORE/DUMP/MODULE/ACL/COMMAND/CLIENT KILL/CLIENT PAUSE/
 * PSUBSCRIBE/SUBSCRIBE/BLPOP/BRPOP/BZPOPMAX/BZPOPMIN/XREAD BLOCK/MULTI/EXEC/WATCH + 任意写命令、未知命令、
 * 代理私有管理命令。归 REDIS_ADMIN/CODE，readonly=false；不可解析者失败关闭。</p>
 *
 * <p><b>资源路径</b>：Redis 资源维度为实例/逻辑 DB/Key 前缀。规范路径为
 * {@code /kpp/<prefix>}（逻辑 DB 由编排者用连接默认库补全为 {@code /rdb/<db>/kpp/<prefix>}，
 * 跨 lane 集成决策，与 SQL 连接器一致）。MGET 中每个 key 独立提取（docs/06 §8.2）。
 * SCAN 的 MATCH 模式（glob）剥离通配后作为前缀。</p>
 *
 * @author DataGate
 */
public class RedisQueryParser implements QueryParser {

    /** 解析器版本（语料回归基线，docs/06 §5.1）。 */
    private static final String PARSER_VERSION = "datagate-redis/1.0";

    /** SCAN COUNT 硬上限（docs/06 §8.2：平台强制限制 COUNT）。 */
    static final int SCAN_COUNT_CAP = 1000;

    /** P0 安全读命令白名单（docs/06 §8.2）。 */
    private static final Set<String> READ_COMMANDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        // 发现
        "SCAN",
        // 通用读取
        "TYPE", "EXISTS", "TTL", "PTTL",
        // String
        "GET", "MGET",
        // Hash
        "HGET", "HMGET", "HGETALL", "HSCAN", "HLEN",
        // List
        "LRANGE", "LLEN",
        // Set
        "SMEMBERS", "SSCAN", "SCARD", "SISMEMBER",
        // ZSet
        "ZRANGE", "ZREVRANGE", "ZREVRANGEBYSCORE", "ZRANGEBYSCORE", "ZSCAN", "ZCARD", "ZSCORE",
        // Stream
        "XRANGE", "XREVRANGE", "XLEN"
    )));

    /** SCAN 类命令（REDIS_SCAN 动作，强制 MATCH 前缀 + COUNT 上限）。 */
    static final Set<String> SCAN_COMMANDS_SET = Set.of("SCAN", "HSCAN", "SSCAN", "ZSCAN");

    /** P0 受控写命令（docs/06 §8.4，经工单，控制台只读默认拒绝）。 */
    private static final Set<String> WRITE_COMMANDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "SET", "SETEX", "SETNX", "SETXX", "GETSET", "INCR", "DECR", "INCRBY", "DECRBY",
        "APPEND", "SETRANGE", "DEL", "UNLINK", "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT",
        "PERSIST", "RENAME", "RENNAMENX", "HSET", "HMSET", "HSETNX", "HDEL", "HINCRBY",
        "LPUSH", "RPUSH", "LPOP", "RPOP", "LREM", "LTRIM", "LSET", "LINSERT", "SADD", "SREM",
        "SPOP", "SMOVE", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE", "ZADD", "ZREM", "ZPOPMAX",
        "ZPOPMIN", "ZINCRBY", "XADD", "XDEL", "XTRIM", "GETDEL"
    )));

    /** 删除类命令（REDIS_DELETE 动作，经工单）。 */
    private static final Set<String> DELETE_COMMANDS = Set.of("DEL", "UNLINK", "HDEL", "LREM",
        "SREM", "ZREM", "XDEL", "GETDEL");

    /** 无条件拒绝命令（docs/06 §8.3）。 */
    private static final Set<String> FORBIDDEN_COMMANDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        // 危险/管理/脚本
        "KEYS", "MONITOR", "CONFIG", "DEBUG", "SHUTDOWN", "SAVE", "BGSAVE", "BGREWRITEAOF",
        "EVAL", "EVALSHA", "FUNCTION", "SCRIPT", "FCALL", "FCALL_RO",
        "FLUSHDB", "FLUSHALL", "MIGRATE", "RESTORE", "DUMP", "COPY",
        "MODULE", "ACL", "COMMAND", "CLUSTER", "FAILOVER", "RESET",
        "PSUBSCRIBE", "SUBSCRIBE", "UNSUBSCRIBE", "PUNSUBSCRIBE", "PUBSUB",
        "BLPOP", "BRPOP", "BZPOPMIN", "BZPOPMAX", "BLMOVE", "BRPOPLPUSH", "BZMPOP", "BLMPOP",
        "XREAD", "XREADGROUP",  // 阻塞读取风险，P0 禁止
        "MULTI", "EXEC", "WATCH", "UNWATCH", "DISCARD",
        "OBJECT", "MEMORY", "LATENCY", "SLOWLOG", "CLIENT", "INFO", "LASTSAVE", "DBSIZE",
        "SWAPDB", "MOVE", "SELECT", "SHUTDOWN", "SLAVEOF", "REPLICAOF", "WAIT",
        "BITOP", "SORT", "SORT_RO"
    )));

    /** 接受 key 作为首参的命令（用于前缀提取）。 */
    private static final Set<String> KEY_AT_ZERO = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "GET", "SET", "SETEX", "SETNX", "GETSET", "DEL", "UNLINK", "EXISTS", "TYPE", "TTL", "PTTL",
        "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST", "RENAME", "APPEND", "INCR", "DECR",
        "INCRBY", "DECRBY", "GETDEL", "GETRANGE", "SETRANGE", "STRLEN",
        "HGET", "HMGET", "HGETALL", "HSCAN", "HLEN", "HSET", "HMSET", "HSETNX", "HDEL", "HINCRBY",
        "HSTRLEN", "HEXISTS",
        "LRANGE", "LLEN", "LINDEX", "LPOP", "RPOP", "LPUSH", "RPUSH", "LSET", "LREM", "LTRIM",
        "LINSERT", "LRANGE",
        "SMEMBERS", "SSCAN", "SCARD", "SISMEMBER", "SADD", "SREM", "SPOP", "SMOVE", "SRANDMEMBER",
        "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZREVRANGEBYSCORE", "ZSCAN", "ZCARD", "ZSCORE",
        "ZADD", "ZREM", "ZPOPMAX", "ZPOPMIN", "ZINCRBY", "ZRANK", "ZREVRANK", "ZCOUNT",
        "XRANGE", "XREVRANGE", "XLEN", "XADD", "XDEL", "XTRIM", "XINFO",
        "MGET"
    )));

    @Override
    public List<ParsedStatement> parse(String statement) {
        if (statement == null || statement.isBlank()) {
            throw fail("空命令", null);
        }
        List<String> tokens = tokenize(statement);
        if (tokens.isEmpty()) {
            throw fail("空命令", null);
        }
        String verb = tokens.get(0).toUpperCase();
        List<String> args = tokens.subList(1, tokens.size());
        // 批次：Redis 命令一次一条，多命令（含管道分隔符）一律失败关闭
        if (statement.indexOf('|') >= 0 || statement.indexOf('\n') >= 0) {
            throw fail("Redis 控制台不支持管道/多命令批次", null);
        }

        Classification cls = classify(verb, args);
        List<String> paths = extractPaths(verb, args);
        String normalized = normalize(verb, args);
        String fingerprint = "redis:" + sha256(normalized);
        return List.of(new ParsedStatement(cls.statementType, paths, normalized, fingerprint,
            cls.action, cls.readonly));
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    // ====================== 分类 ======================

    private Classification classify(String verb, List<String> args) {
        // 无条件拒绝命令（docs/06 §8.3）
        if (FORBIDDEN_COMMANDS.contains(verb)) {
            // CLIENT KILL / CLIENT PAUSE 单独归类为管理动作（CLIENT 整体在禁止集）
            return new Classification(verb, DbAction.REDIS_ADMIN, false);
        }
        // 安全读白名单（docs/06 §8.2）
        if (SCAN_COMMANDS_SET.contains(verb)) {
            return new Classification(verb, DbAction.REDIS_SCAN, true);
        }
        if (READ_COMMANDS.contains(verb)) {
            return new Classification(verb, DbAction.REDIS_READ, true);
        }
        // 删除命令 → REDIS_DELETE（经工单，控制台只读拒绝）
        if (DELETE_COMMANDS.contains(verb)) {
            return new Classification(verb, DbAction.REDIS_DELETE, false);
        }
        // 写命令 → REDIS_WRITE（经工单，控制台只读拒绝）
        if (WRITE_COMMANDS.contains(verb)) {
            return new Classification(verb, DbAction.REDIS_WRITE, false);
        }
        // 未知命令 → 失败关闭（docs/06 §8.3「未知命令」）
        throw fail("未知/不允许的 Redis 命令: " + verb, null);
    }

    // ====================== 资源路径提取 ======================

    /**
     * 提取 Key 前缀资源路径（docs/06 §8.1、§8.2）。
     * <ul>
     *   <li>SCAN/HSCAN/SSCAN/ZSCAN：MATCH 模式（glob 剥离通配）作为前缀；</li>
     *   <li>MGET：每个 key 独立提取（§8.2）；</li>
     *   <li>其它接受 key 的命令：首参 key → 派生前缀。</li>
     * </ul>
     */
    private List<String> extractPaths(String verb, List<String> args) {
        List<String> paths = new ArrayList<>();
        if (SCAN_COMMANDS_SET.contains(verb)) {
            // SCAN [cursor] [MATCH pattern] [COUNT n]
            String match = findOption(args, "MATCH");
            if (match != null && !match.isBlank()) {
                paths.add("/kpp/" + globToPrefix(match));
            }
            return paths;
        }
        if ("MGET".equals(verb)) {
            // 每个 key 独立校验（docs/06 §8.2）
            for (String key : args) {
                if (key != null && !key.isBlank()) {
                    paths.add("/kpp/" + derivePrefix(key));
                }
            }
            return paths;
        }
        if (KEY_AT_ZERO.contains(verb) && !args.isEmpty()) {
            String key = args.get(0);
            if (key != null && !key.isBlank()) {
                paths.add("/kpp/" + derivePrefix(key));
            }
            return paths;
        }
        return paths;
    }

    /** 查找命令选项值（如 MATCH pattern）。 */
    private static String findOption(List<String> args, String option) {
        String opt = option.toUpperCase();
        for (int i = 0; i + 1 < args.size(); i++) {
            if (opt.equals(args.get(i).toUpperCase())) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    /**
     * 由 key 派生授权前缀（Redis key 命名约定 namespace:ident）。
     * 含冒号：取至最后一个冒号（含）作为前缀，如 user:123 → user:。
     * 不含冒号：以整个 key 作为前缀（由资源目录裁定最长前缀匹配）。
     */
    static String derivePrefix(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        int idx = key.lastIndexOf(':');
        if (idx >= 0) {
            return key.substring(0, idx + 1);
        }
        return key;
    }

    /** SCAN glob 模式剥离通配后作为前缀（user:* → user:）。 */
    static String globToPrefix(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return "";
        }
        // 剥离尾部 * 与 ? 通配
        String p = pattern;
        // 去除末尾连续通配符
        while (!p.isEmpty() && (p.charAt(p.length() - 1) == '*' || p.charAt(p.length() - 1) == '?')) {
            p = p.substring(0, p.length() - 1);
        }
        // 若模式仍含 * → 不可作为精确前缀，取首个 * 前的稳定前缀
        int star = p.indexOf('*');
        if (star >= 0) {
            p = p.substring(0, star);
        }
        return p.isEmpty() ? pattern : p;
    }

    // ====================== 分词与归一化 ======================

    /**
     * RESP 风格分词：按空白拆分，双引号内保留空格与分号（不接受原始文本拼接，docs/06 §8.1）。
     */
    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                if (inQuote) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    inQuote = false;
                } else {
                    inQuote = true;
                }
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        // 未闭合引号 → 失败关闭
        if (inQuote) {
            throw fail("未闭合的引号", null);
        }
        return tokens;
    }

    /** 归一化：verb 大写 + 参数（key 原样保留大小写）。 */
    private static String normalize(String verb, List<String> args) {
        StringBuilder sb = new StringBuilder(verb);
        for (String a : args) {
            sb.append(' ').append(a);
        }
        return sb.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static DbServiceException fail(String reason, Throwable cause) {
        if (cause != null) {
            return new DbServiceException(DbErrorCode.QUERY_PARSE_FAILED,
                "命令解析失败(" + reason + "): " + cause.getClass().getSimpleName());
        }
        return new DbServiceException(DbErrorCode.QUERY_PARSE_FAILED, "命令解析失败(" + reason + ")");
    }

    private record Classification(String statementType, DbAction action, boolean readonly) {
    }
}
