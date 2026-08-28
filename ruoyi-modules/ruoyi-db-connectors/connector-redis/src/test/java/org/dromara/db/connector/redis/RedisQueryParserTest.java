package org.dromara.db.connector.redis;

import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.error.DbServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedisQueryParser 单元测试（REDIS-101 / docs/06 §8.2、§8.3、§15 安全语料）。
 *
 * <p>纯逻辑测试，不连接 Redis。覆盖：安全读白名单、SCAN 前缀提取、MGET 独立校验、
 * 强制拒绝语料（KEYS/EVAL/FLUSHDB/CONFIG/写命令/未知命令）、失败关闭、分词韧性。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("RedisQueryParser 安全语料测试 (REDIS-101)")
class RedisQueryParserTest {

    private final RedisQueryParser parser = new RedisQueryParser();

    private ParsedStatement one(String cmd) {
        List<ParsedStatement> list = parser.parse(cmd);
        assertEquals(1, list.size(), "预期单条命令: " + cmd);
        return list.get(0);
    }

    // ====================== 安全读白名单：readonly=true ======================

    @Test
    @DisplayName("允许：GET -> REDIS_READ, 前缀派生")
    void allowGet() {
        ParsedStatement s = one("GET user:123");
        assertEquals(DbAction.REDIS_READ, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/kpp/user:"),
            "前缀 user:: " + s.resourcePaths());
        assertNormalizedAndFingerprint(s);
    }

    @Test
    @DisplayName("允许：GET 无冒号 key -> 整 key 作前缀")
    void allowGetNoColon() {
        ParsedStatement s = one("GET simplekey");
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/kpp/simplekey"), "无冒号整 key 作前缀");
    }

    @Test
    @DisplayName("允许：TYPE/EXISTS/TTL/PTTL -> REDIS_READ")
    void allowCommonRead() {
        for (String cmd : new String[]{"TYPE user:1", "EXISTS user:1", "TTL user:1", "PTTL user:1"}) {
            ParsedStatement s = one(cmd);
            assertEquals(DbAction.REDIS_READ, s.requiredAction(), cmd);
            assertTrue(s.readonly(), cmd);
            assertTrue(s.resourcePaths().contains("/kpp/user:"), cmd);
        }
    }

    @Test
    @DisplayName("允许：MGET -> 每个 key 独立提取（§8.2）")
    void allowMget() {
        ParsedStatement s = one("MGET user:1 order:2 product:3");
        assertEquals(DbAction.REDIS_READ, s.requiredAction());
        assertTrue(s.readonly());
        List<String> paths = s.resourcePaths();
        assertTrue(paths.contains("/kpp/user:"), "含 user: " + paths);
        assertTrue(paths.contains("/kpp/order:"), "含 order: " + paths);
        assertTrue(paths.contains("/kpp/product:"), "含 product: " + paths);
    }

    @Test
    @DisplayName("允许：HGET/HGETALL/HLEN/LRANGE/LLEN/SMEMBERS/SCARD/SISMEMBER/ZRANGE/ZCARD/ZSCORE/XLEN -> REDIS_READ")
    void allowTypeReads() {
        for (String cmd : new String[]{
            "HGET user:1 field", "HGETALL user:1", "HLEN user:1", "LRANGE user:1 0 10",
            "LLEN user:1", "SMEMBERS user:1", "SCARD user:1", "SISMEMBER user:1 m",
            "ZRANGE user:1 0 10", "ZCARD user:1", "ZSCORE user:1 m", "XLEN user:1"
        }) {
            ParsedStatement s = one(cmd);
            assertEquals(DbAction.REDIS_READ, s.requiredAction(), cmd);
            assertTrue(s.readonly(), cmd);
            assertTrue(s.resourcePaths().contains("/kpp/user:"), cmd);
        }
    }

    @Test
    @DisplayName("允许：SCAN -> REDIS_SCAN, MATCH 前缀提取")
    void allowScan() {
        ParsedStatement s = one("SCAN 0 MATCH user:* COUNT 100");
        assertEquals(DbAction.REDIS_SCAN, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/kpp/user:"),
            "MATCH 前缀剥离通配: " + s.resourcePaths());
    }

    @Test
    @DisplayName("允许：SCAN 无 MATCH -> 无前缀资源")
    void allowScanNoMatch() {
        ParsedStatement s = one("SCAN 0");
        assertEquals(DbAction.REDIS_SCAN, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().isEmpty(), "无 MATCH 无前缀: " + s.resourcePaths());
    }

    @Test
    @DisplayName("允许：HSCAN/SSCAN/ZSCAN -> REDIS_SCAN")
    void allowSubScans() {
        assertTrue(one("HSCAN user:1 0").requiredAction() == DbAction.REDIS_SCAN);
        assertTrue(one("SSCAN user:1 0").requiredAction() == DbAction.REDIS_SCAN);
        assertTrue(one("ZSCAN user:1 0").requiredAction() == DbAction.REDIS_SCAN);
    }

    // ====================== 强制拒绝语料（§8.3）======================

    static Stream<Arguments> forcedRejectCorpus() {
        // docs/06 §8.3：每条强制拒绝项归为 readonly=false 且动作为写/删除/管理/代码
        return Stream.of(
            // 脚本/管理/危险命令 -> REDIS_ADMIN
            Arguments.of("KEYS user:*", DbAction.REDIS_ADMIN),
            Arguments.of("MONITOR", DbAction.REDIS_ADMIN),
            Arguments.of("CONFIG GET maxmemory", DbAction.REDIS_ADMIN),
            Arguments.of("DEBUG sleep 1", DbAction.REDIS_ADMIN),
            Arguments.of("FLUSHDB", DbAction.REDIS_ADMIN),
            Arguments.of("FLUSHALL", DbAction.REDIS_ADMIN),
            Arguments.of("EVAL \"return 1\" 0", DbAction.REDIS_ADMIN),
            Arguments.of("EVALSHA abc 0", DbAction.REDIS_ADMIN),
            Arguments.of("SCRIPT LOAD \"return 1\"", DbAction.REDIS_ADMIN),
            Arguments.of("FUNCTION LOAD \"#lua\"", DbAction.REDIS_ADMIN),
            Arguments.of("MIGRATE 127.0.0.1 6379 k 0 0", DbAction.REDIS_ADMIN),
            Arguments.of("RESTORE k 0 abc", DbAction.REDIS_ADMIN),
            Arguments.of("MODULE LOAD /x.so", DbAction.REDIS_ADMIN),
            Arguments.of("ACL WHOAMI", DbAction.REDIS_ADMIN),
            Arguments.of("CLIENT KILL 1", DbAction.REDIS_ADMIN),
            Arguments.of("SUBSCRIBE ch", DbAction.REDIS_ADMIN),
            Arguments.of("BLPOP user:1 0", DbAction.REDIS_ADMIN),
            Arguments.of("BRPOP user:1 0", DbAction.REDIS_ADMIN),
            Arguments.of("MULTI", DbAction.REDIS_ADMIN),
            Arguments.of("EXEC", DbAction.REDIS_ADMIN),
            Arguments.of("WATCH user:1", DbAction.REDIS_ADMIN),
            Arguments.of("SELECT 0", DbAction.REDIS_ADMIN),
            Arguments.of("SHUTDOWN", DbAction.REDIS_ADMIN),
            // 删除命令 -> REDIS_DELETE
            Arguments.of("DEL user:1", DbAction.REDIS_DELETE),
            Arguments.of("UNLINK user:1", DbAction.REDIS_DELETE),
            Arguments.of("HDEL user:1 f", DbAction.REDIS_DELETE),
            Arguments.of("SREM user:1 m", DbAction.REDIS_DELETE),
            Arguments.of("ZREM user:1 m", DbAction.REDIS_DELETE),
            // 写命令 -> REDIS_WRITE
            Arguments.of("SET user:1 v", DbAction.REDIS_WRITE),
            Arguments.of("SETEX user:1 10 v", DbAction.REDIS_WRITE),
            Arguments.of("INCR user:1", DbAction.REDIS_WRITE),
            Arguments.of("HSET user:1 f v", DbAction.REDIS_WRITE),
            Arguments.of("LPUSH user:1 v", DbAction.REDIS_WRITE),
            Arguments.of("EXPIRE user:1 10", DbAction.REDIS_WRITE)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("forcedRejectCorpus")
    @DisplayName("强制拒绝语料：readonly=false 且动作为写/删除/管理")
    void forcedReject(String cmd, DbAction expectedAction) {
        ParsedStatement s = one(cmd);
        assertFalse(s.readonly(), "强制拒绝项必须 readonly=false: " + cmd);
        assertEquals(expectedAction, s.requiredAction(),
            "动作分类不符: " + cmd + " -> " + s.requiredAction());
    }

    // ====================== 失败关闭（§8.3 未知命令）======================

    static Stream<String> failClosedCorpus() {
        return Stream.of(
            "FOO user:1",                       // 未知命令
            "GETBAR user:1",                    // 未知命令
            "GET \"unclosed",                   // 未闭合引号
            "GET user:1 | DEL user:1",          // 管道多命令
            "GET user:1\nDEL user:1"            // 换行多命令
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("failClosedCorpus")
    @DisplayName("失败关闭：未知/多命令/未闭合引号抛 QUERY_PARSE_FAILED")
    void failClosed(String cmd) {
        DbServiceException ex = assertThrows(DbServiceException.class, () -> parser.parse(cmd),
            "必须失败关闭: " + cmd);
        assertEquals("QUERY_PARSE_FAILED", ex.getErrorCode().name());
    }

    // ====================== 分词韧性 ======================

    @Test
    @DisplayName("韧性：双引号内空格保留为单参")
    void quotedSpacePreserved() {
        ParsedStatement s = one("GET \"user:with space\"");
        assertTrue(s.readonly());
        // 引号内 user:with space → 派生前缀 user:
        assertTrue(s.resourcePaths().contains("/kpp/user:"), "引号内 key 前缀: " + s.resourcePaths());
    }

    @Test
    @DisplayName("韧性：命令大小写不敏感")
    void caseInsensitiveVerb() {
        ParsedStatement s = one("get user:1");
        assertEquals(DbAction.REDIS_READ, s.requiredAction());
        assertTrue(s.readonly());
    }

    // ====================== 解析器版本 ======================

    @Test
    @DisplayName("parserVersion() 锁定并返回")
    void parserVersionLocked() {
        String v = parser.parserVersion();
        assertNotNull(v);
        assertTrue(v.startsWith("datagate-redis"), "解析器版本前缀: " + v);
    }

    // ====================== 前缀派生辅助 ======================

    @Test
    @DisplayName("前缀派生：含冒号取至最后冒号")
    void derivePrefixColon() {
        assertEquals("user:", RedisQueryParser.derivePrefix("user:123"));
        assertEquals("app:user:", RedisQueryParser.derivePrefix("app:user:456"));
    }

    @Test
    @DisplayName("前缀派生：无冒号整 key")
    void derivePrefixNoColon() {
        assertEquals("simplekey", RedisQueryParser.derivePrefix("simplekey"));
    }

    @Test
    @DisplayName("glob 前缀：剥离尾部通配")
    void globPrefix() {
        assertEquals("user:", RedisQueryParser.globToPrefix("user:*"));
        assertEquals("user:", RedisQueryParser.globToPrefix("user:"));
        assertEquals("order", RedisQueryParser.globToPrefix("order*"));
    }

    private static void assertNormalizedAndFingerprint(ParsedStatement s) {
        assertNotNull(s.normalizedStatement());
        assertFalse(s.normalizedStatement().isBlank(), "归一化命令非空");
        assertTrue(s.fingerprint().startsWith("redis:"), "方言指纹前缀: " + s.fingerprint());
    }
}
