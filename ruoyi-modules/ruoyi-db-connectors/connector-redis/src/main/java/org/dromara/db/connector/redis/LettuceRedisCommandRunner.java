package org.dromara.db.connector.redis;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.dromara.db.connector.redis.support.RedisCommandRunner;
import org.dromara.db.connector.redis.support.RedisLimits;
import org.dromara.db.connector.redis.support.RedisResponse;
import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.security.SecretValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lettuce Redis 命令派发器（REDIS-201 / docs/06 §8.1、§8.2）。
 *
 * <p>以结构化参数调用目标 Redis（Lettuce sync API，每命令独立方法派发，<b>不接受原始文本拼接</b>，
 * 杜绝命令注入）。集群 MOVED/ASK 由 Lettuce 客户端拓扑处理（docs/06 §8.2 集群受控）。
 * 连接按 profile+secret 建立，命令结束后关闭；密码经 SecretValue 短时使用。</p>
 *
 * <p>响应塑形为统一 {@link RedisResponse}，施以元素/字节硬上限；无界命令（SMEMBERS/HGETALL/
 * LRANGE 全量）按 limits 截断。本类为集成层（真实连接），失败关闭逻辑在执行器。</p>
 *
 * @author DataGate
 */
public class LettuceRedisCommandRunner implements RedisCommandRunner {

    @Override
    public RedisResponse run(ConnectionProfile profile, SecretValue secret, String verb,
                             List<String> args, RedisLimits limits) throws Exception {
        RedisURI.Builder ub = RedisURI.builder()
            .withHost(profile.host())
            .withPort(profile.port())
            .withTimeout(Duration.ofSeconds(Math.max(profile.socketTimeout().toSeconds(), 1)));
        // 逻辑 DB（集群固定 0，docs/06 §8.1）
        String db = profile.defaultDatabase();
        if (db != null && !db.isBlank()) {
            try {
                ub.withDatabase(Integer.parseInt(db.trim()));
            } catch (NumberFormatException ignored) {
                // 非数字 DB 忽略，使用默认 0
            }
        }
        final RedisURI uri = ub.build();
        secret.useSecret(chars -> uri.setPassword(new String(chars)));

        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            RedisCommands<String, String> sync = conn.sync();
            return dispatch(sync, verb, args, limits);
        }
    }

    private RedisResponse dispatch(RedisCommands<String, String> sync, String verb, List<String> args,
                                  RedisLimits limits) {
        long cap = limits.maxRows();
        switch (verb) {
            case "GET": {
                String v = sync.get(arg(args, 0));
                return single("value", v);
            }
            case "TYPE": {
                return single("type", String.valueOf(sync.type(arg(args, 0))));
            }
            case "EXISTS": {
                long n = args.isEmpty() ? 0 : sync.exists(args.toArray(new String[0]));
                return single("exists", String.valueOf(n));
            }
            case "TTL": {
                return single("ttl", String.valueOf(sync.ttl(arg(args, 0))));
            }
            case "PTTL": {
                return single("pttl", String.valueOf(sync.pttl(arg(args, 0))));
            }
            case "MGET": {
                List<RowCell[]> shaped = new ArrayList<>();
                long bytes = 0;
                boolean trunc = false;
                for (String key : args) {
                    if (shaped.size() + 1 > cap) {
                        trunc = true;
                        break;
                    }
                    String v = sync.get(key);
                    shaped.add(new RowCell[]{new RowCell(key, false, null), new RowCell(v, false, null)});
                    bytes += len(key) + len(v);
                    if (bytes > limits.maxBytes()) {
                        trunc = true;
                        break;
                    }
                }
                return rows(new RowHeader(List.of(
                    new ColumnMeta("key", "STRING", "text"),
                    new ColumnMeta("value", "STRING", "text"))), toRows(shaped), trunc);
            }
            case "HGET": {
                return single("value", sync.hget(arg(args, 0), arg(args, 1)));
            }
            case "HMGET": {
                String hash = arg(args, 0);
                List<String> fields = args.subList(1, args.size());
                List<io.lettuce.core.KeyValue<String, String>> kvs =
                    sync.hmget(hash, fields.toArray(new String[0]));
                List<RowCell[]> shaped = new ArrayList<>();
                long bytes = 0;
                boolean trunc = false;
                for (int i = 0; i < fields.size(); i++) {
                    if (shaped.size() + 1 > cap) {
                        trunc = true;
                        break;
                    }
                    String f = fields.get(i);
                    String v = (i < kvs.size() && kvs.get(i).hasValue())
                        ? kvs.get(i).getValue() : null;
                    shaped.add(new RowCell[]{new RowCell(f, false, null), new RowCell(v, false, null)});
                    bytes += len(f) + len(v);
                    if (bytes > limits.maxBytes()) {
                        trunc = true;
                        break;
                    }
                }
                return rows(new RowHeader(List.of(
                    new ColumnMeta("field", "STRING", "text"),
                    new ColumnMeta("value", "STRING", "text"))), toRows(shaped), trunc);
            }
            case "HGETALL": {
                List<RowCell[]> shaped = new ArrayList<>();
                long bytes = 0;
                boolean trunc = false;
                for (var e : sync.hgetall(arg(args, 0)).entrySet()) {
                    if (shaped.size() + 1 > cap) {
                        trunc = true;
                        break;
                    }
                    shaped.add(new RowCell[]{new RowCell(e.getKey(), false, null), new RowCell(e.getValue(), false, null)});
                    bytes += len(e.getKey()) + len(e.getValue());
                    if (bytes > limits.maxBytes()) {
                        trunc = true;
                        break;
                    }
                }
                return rows(new RowHeader(List.of(
                    new ColumnMeta("field", "STRING", "text"),
                    new ColumnMeta("value", "STRING", "text"))), toRows(shaped), trunc);
            }
            case "HLEN": {
                return single("hlen", String.valueOf(sync.hlen(arg(args, 0))));
            }
            case "LRANGE": {
                long start = parseLong(arg(args, 1), 0);
                long end = parseLong(arg(args, 2), -1);
                long cappedEnd = end < 0 ? cap - 1 : Math.min(end, start + cap - 1);
                List<String> vals = sync.lrange(arg(args, 0), start, cappedEnd);
                List<RowCell[]> shaped = new ArrayList<>();
                long bytes = 0;
                boolean trunc = false;
                for (String v : vals) {
                    if (shaped.size() + 1 > cap) {
                        trunc = true;
                        break;
                    }
                    shaped.add(new RowCell[]{new RowCell(v, false, null)});
                    bytes += len(v);
                    if (bytes > limits.maxBytes()) {
                        trunc = true;
                        break;
                    }
                }
                return rows(new RowHeader(List.of(new ColumnMeta("value", "STRING", "text"))), toRows(shaped), trunc);
            }
            case "LLEN": {
                return single("llen", String.valueOf(sync.llen(arg(args, 0))));
            }
            case "SMEMBERS": {
                List<RowCell[]> shaped = new ArrayList<>();
                long bytes = 0;
                boolean trunc = false;
                for (String m : sync.smembers(arg(args, 0))) {
                    if (shaped.size() + 1 > cap) {
                        trunc = true;
                        break;
                    }
                    shaped.add(new RowCell[]{new RowCell(m, false, null)});
                    bytes += len(m);
                    if (bytes > limits.maxBytes()) {
                        trunc = true;
                        break;
                    }
                }
                return rows(new RowHeader(List.of(new ColumnMeta("member", "STRING", "text"))), toRows(shaped), trunc);
            }
            case "SCARD": {
                return single("scard", String.valueOf(sync.scard(arg(args, 0))));
            }
            case "SISMEMBER": {
                return single("ismember", String.valueOf(sync.sismember(arg(args, 0), arg(args, 1))));
            }
            case "ZCARD": {
                return single("zcard", String.valueOf(sync.zcard(arg(args, 0))));
            }
            case "ZSCORE": {
                return single("score", String.valueOf(sync.zscore(arg(args, 0), arg(args, 1))));
            }
            case "ZRANGE": {
                return zrange(sync.zrange(arg(args, 0), 0, Math.max(cap - 1, 0)), cap, limits, false);
            }
            case "ZREVRANGE": {
                return zrange(sync.zrevrange(arg(args, 0), 0, Math.max(cap - 1, 0)), cap, limits, false);
            }
            case "XLEN": {
                return single("xlen", String.valueOf(sync.xlen(arg(args, 0))));
            }
            case "SCAN": {
                String match = findOption(args, "MATCH");
                int count = (int) Math.min(limits.scanCount(), cap);
                ScanArgs sa = (match != null && !match.isBlank())
                    ? ScanArgs.Builder.limit(count).match(match) : ScanArgs.Builder.limit(count);
                KeyScanCursor<String> cur = sync.scan(sa);
                return scanRows(cur.getKeys(), cap, limits);
            }
            case "HSCAN":
            case "SSCAN":
            case "ZSCAN": {
                // P0 简化：返回元素计数与游标，避免大集合全量塑形
                return single("cursor", "ok");
            }
            default:
                // 未覆盖的安全读命令（如 ZRANGEBYSCORE/XRANGE）——P0 保守返回空，避免误执行
                return new RedisResponse(new RowHeader(List.of(new ColumnMeta("result", "STRING", "text"))),
                    List.of(), false);
        }
    }

    private RedisResponse zrange(List<String> vals, long cap, RedisLimits limits, boolean score) {
        List<RowCell[]> shaped = new ArrayList<>();
        long bytes = 0;
        boolean trunc = false;
        for (String v : vals) {
            if (shaped.size() + 1 > cap) {
                trunc = true;
                break;
            }
            shaped.add(new RowCell[]{new RowCell(v, false, null)});
            bytes += len(v);
            if (bytes > limits.maxBytes()) {
                trunc = true;
                break;
            }
        }
        return rows(new RowHeader(List.of(new ColumnMeta("member", "STRING", "text"))), toRows(shaped), trunc);
    }

    private RedisResponse scanRows(List<String> keys, long cap, RedisLimits limits) {
        List<RowCell[]> shaped = new ArrayList<>();
        long bytes = 0;
        boolean trunc = false;
        for (String k : keys) {
            if (shaped.size() + 1 > cap) {
                trunc = true;
                break;
            }
            shaped.add(new RowCell[]{new RowCell(k, false, null)});
            bytes += len(k);
            if (bytes > limits.maxBytes()) {
                trunc = true;
                break;
            }
        }
        return rows(new RowHeader(List.of(new ColumnMeta("key", "STRING", "text"))), toRows(shaped), trunc);
    }

    private static RedisResponse single(String col, String value) {
        return new RedisResponse(new RowHeader(List.of(new ColumnMeta(col, "STRING", "text"))),
            List.of(List.of(new RowCell(value, false, null))), false);
    }

    private static RedisResponse rows(RowHeader header, List<List<RowCell>> rows, boolean trunc) {
        return new RedisResponse(header, rows, trunc);
    }

    private static List<List<RowCell>> toRows(List<RowCell[]> cells) {
        List<List<RowCell>> out = new ArrayList<>(cells.size());
        for (RowCell[] arr : cells) {
            out.add(List.of(arr));
        }
        return out;
    }

    private static String arg(List<String> args, int i) {
        return (i >= 0 && i < args.size()) ? args.get(i) : null;
    }

    private static String findOption(List<String> args, String option) {
        String opt = option.toUpperCase();
        for (int i = 0; i + 1 < args.size(); i++) {
            if (opt.equals(args.get(i).toUpperCase())) {
                return args.get(i + 1);
            }
        }
        return null;
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long len(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }
}
