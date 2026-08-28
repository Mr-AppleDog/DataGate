package org.dromara.db.connector.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import org.dromara.db.connector.redis.support.RedisSlowlogDiffer;
import org.dromara.db.connector.redis.support.RedisSlowlogDiffer.DiffResult;
import org.dromara.db.connector.redis.support.RedisSlowlogDiffer.SlowlogEntry;
import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.SlowQueryProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis SLOWLOG 采集器（docs/07 §4.4）。
 *
 * 使用独立监控账号执行 SLOWLOG GET；以 slowlog id 为游标（单调递增）。
 * id 回退（SLOWLOG RESET 或实例切换/重启）时重建基线，全部当新事件。
 * 命令参数只保留命令名与参数个数，禁止保存 value（密码/Token/手机号等）。
 * nativeFingerprint=null（Redis 无原生查询哈希）；portableFingerprint=SHA-256(命令模板)；
 * parserVersion=redis-slowlog；ingestQuality=COMPLETE（逐次事件，非摘要）。
 *
 * @author DataGate
 */
public class RedisSlowQueryProvider implements SlowQueryProvider {

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public SlowQueryPage pull(ConnectionProfile profile, SecretValue secret,
                              CollectorCursor cursor, int limit) {
        CursorState state = parseCursor(cursor);
        long epoch = state.epoch + 1;
        Instant now = Instant.now();

        List<Object> raw = fetchSlowlog(profile, secret, Math.max(limit, 1));
        List<SlowlogEntry> entries = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof List)) {
                continue;
            }
            List<?> entry = (List<?>) o;
            if (entry.size() < 4) {
                continue;
            }
            long id = toLong(entry.get(0));
            long ts = toLong(entry.get(1));
            long dur = toLong(entry.get(2));
            List<?> args = entry.get(3) instanceof List ? (List<?>) entry.get(3) : List.of();
            String verb = args.isEmpty() ? "UNKNOWN" : String.valueOf(args.get(0)).toUpperCase();
            int argc = Math.max(args.size() - 1, 0);
            entries.add(new SlowlogEntry(id, ts, dur, verb, argc));
        }

        DiffResult diff = RedisSlowlogDiffer.collect(entries, state.lastId, now);
        long newLastId = diff.newLastId();

        String nextCursorJson = buildCursor(epoch, newLastId);
        CollectorCursor nextCursor = new CollectorCursor(
            cursor == null ? null : cursor.sourceId(),
            cursor == null ? "default" : cursor.partitionKey(),
            nextCursorJson,
            now,
            cursor == null ? 0L : cursor.version()
        );
        return new SlowQueryPage(diff.records(), nextCursor);
    }

    private List<Object> fetchSlowlog(ConnectionProfile profile, SecretValue secret, int count) {
        RedisURI.Builder ub = RedisURI.builder()
            .withHost(profile.host())
            .withPort(profile.port())
            .withTimeout(Duration.ofSeconds(Math.max(profile.socketTimeout().toSeconds(), 1)));
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
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
            .add("GET").add(String.valueOf(count));
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            RedisCommands<String, String> sync = conn.sync();
            List<Object> result = sync.<List<Object>>dispatch(CommandType.SLOWLOG, new NestedMultiOutput<>(StringCodec.UTF8), args);
            return result == null ? List.of() : result;
        } catch (Exception e) {
            throw new DbServiceException(DbErrorCode.QUERY_ENGINE_UNAVAILABLE,
                "采集连接失败: " + e.getClass().getSimpleName());
        }
    }

    private CursorState parseCursor(CollectorCursor cursor) {
        if (cursor == null || cursor.cursor() == null || cursor.cursor().isBlank()) {
            return new CursorState(0L, 0L);
        }
        try {
            Map<String, Object> root = OM.readValue(cursor.cursor(), new TypeReference<Map<String, Object>>() {});
            long epoch = ((Number) root.getOrDefault("epoch", 0L)).longValue();
            long lastId = ((Number) root.getOrDefault("lastId", 0L)).longValue();
            return new CursorState(epoch, lastId);
        } catch (Exception e) {
            return new CursorState(0L, 0L);
        }
    }

    private String buildCursor(long epoch, long lastId) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("epoch", epoch);
        root.put("lastId", lastId);
        try {
            return OM.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private record CursorState(long epoch, long lastId) {
    }
}
