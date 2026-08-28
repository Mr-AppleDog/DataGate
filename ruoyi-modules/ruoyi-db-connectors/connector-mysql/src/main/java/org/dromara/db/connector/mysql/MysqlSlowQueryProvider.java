package org.dromara.db.connector.mysql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.db.connector.mysql.support.SlowQuerySnapshotDiffer;
import org.dromara.db.connector.mysql.support.SlowQuerySnapshotDiffer.DigestDiff;
import org.dromara.db.connector.mysql.support.SlowQuerySnapshotDiffer.DigestSnapshot;
import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.SlowQueryProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * MySQL 慢查询采集器（docs/07 §4.2）。
 *
 * 优先级 2：performance_schema.events_statements_summary_by_digest 摘要差值。
 * 摘要非逐次原始事件，标 AGGREGATED；保存计数/总耗时快照，差值生成时间桶。
 * COUNT_STAR 回退视为统计重置，重建基线不产出负指标。
 *
 * DIGEST_TEXT 已由 MySQL 参数化（字面量→?），nativeFingerprint=DIGEST；
 * portableFingerprint=SHA-256(敏感清理后 DIGEST_TEXT)；parserVersion=mysql-perf_schema。
 *
 * 连接使用 DriverManager 短连接（1/分钟轻量查询，connect/socket 超时来自 profile）；
 * 监控账号独立于查询/变更账号（docs/07 §4.1）。
 *
 * @author DataGate
 */
public class MysqlSlowQueryProvider implements SlowQueryProvider {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String PARSER_VERSION = "mysql-perf_schema";
    private static final long PS_TO_MICROS = 1_000_000L;

    private static final Pattern SECRET_LIT = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|token|authorization)\\s*[=:]\\s*\\S+");

    private static final String SQL =
        "SELECT DIGEST, DIGEST_TEXT, SCHEMA_NAME, COUNT_STAR, SUM_TIMER_WAIT, " +
        "SUM_LOCK_TIME, SUM_ROWS_EXAMINED, SUM_ROWS_SENT, FIRST_SEEN, LAST_SEEN " +
        "FROM performance_schema.events_statements_summary_by_digest " +
        "WHERE DIGEST_TEXT IS NOT NULL ORDER BY LAST_SEEN DESC LIMIT ?";

    private final MysqlConnector connector;

    public MysqlSlowQueryProvider(MysqlConnector connector) {
        this.connector = connector;
    }

    @Override
    public SlowQueryPage pull(ConnectionProfile profile, SecretValue secret,
                              CollectorCursor cursor, int limit) {
        CursorState state = parseCursor(cursor);
        Map<String, DigestSnapshot> current = fetchCurrent(profile, secret, Math.max(limit, 1));
        List<DigestDiff> diffs = SlowQuerySnapshotDiffer.diff(current, state.snapshots);

        long epoch = state.epoch + 1;
        Instant now = Instant.now();
        List<SlowQueryRecord> records = new ArrayList<>();
        for (DigestDiff d : diffs) {
            if (d.reset() || d.count() <= 0) {
                continue;
            }
            String normalized = sanitize(d.digestText());
            String fingerprint = sha256(normalized);
            String sourceKey = "agg:" + d.digest() + ":" + epoch;
            records.add(new SlowQueryRecord(
                sourceKey,
                d.digest(),
                null,
                "MYSQL",
                d.schema(),
                fingerprint,
                d.digest(),
                PARSER_VERSION,
                normalized,
                null,
                Instant.ofEpochMilli(d.lastSeenMillis()),
                now,
                d.timer() / PS_TO_MICROS,
                d.lockTime() / PS_TO_MICROS,
                d.rowsExamined(),
                d.rowsSent(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "AGGREGATED"
            ));
        }

        // 下一游标：当前快照 + 递增 epoch（每轮唯一，保证同 digest 多轮差值各自落盘不被去重误杀）
        String nextCursorJson = buildCursor(current, epoch);
        CollectorCursor nextCursor = new CollectorCursor(
            cursor == null ? null : cursor.sourceId(),
            cursor == null ? "default" : cursor.partitionKey(),
            nextCursorJson,
            current.isEmpty() ? null : latestLastSeen(current),
            cursor == null ? 0L : cursor.version()
        );
        return new SlowQueryPage(records, nextCursor);
    }

    private Map<String, DigestSnapshot> fetchCurrent(ConnectionProfile profile, SecretValue secret, int limit) {
        Properties props = new Properties();
        props.setProperty("user", profile.username() == null ? "" : profile.username());
        secret.useSecret(chars -> props.setProperty("password", new String(chars)));
        Map<String, DigestSnapshot> map = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(connector.buildJdbcUrl(profile), props);
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String digest = rs.getString("DIGEST");
                    String digestText = rs.getString("DIGEST_TEXT");
                    String schema = rs.getString("SCHEMA_NAME");
                    long count = rs.getLong("COUNT_STAR");
                    long timer = rs.getLong("SUM_TIMER_WAIT");
                    long lockTime = rs.getLong("SUM_LOCK_TIME");
                    long rowsExamined = rs.getLong("SUM_ROWS_EXAMINED");
                    long rowsSent = rs.getLong("SUM_ROWS_SENT");
                    long firstSeen = rs.getTimestamp("FIRST_SEEN") == null ? 0L : rs.getTimestamp("FIRST_SEEN").getTime();
                    long lastSeen = rs.getTimestamp("LAST_SEEN") == null ? System.currentTimeMillis() : rs.getTimestamp("LAST_SEEN").getTime();
                    map.put(digest, new DigestSnapshot(digest, digestText, schema,
                        count, timer, lockTime, rowsExamined, rowsSent, firstSeen, lastSeen));
                }
            }
        } catch (Exception e) {
            // 不向上抛驱动异常（含主机/用户名信息），抛平台错误码由编排层标记采集失败（docs/07 §13）
            throw new DbServiceException(DbErrorCode.QUERY_ENGINE_UNAVAILABLE,
                "采集连接失败: " + e.getClass().getSimpleName());
        } finally {
            props.remove("password");
        }
        return map;
    }

    private CursorState parseCursor(CollectorCursor cursor) {
        if (cursor == null || cursor.cursor() == null || cursor.cursor().isBlank()) {
            return new CursorState(0L, Map.of());
        }
        try {
            Map<String, Object> root = OM.readValue(cursor.cursor(), new TypeReference<Map<String, Object>>() {});
            long epoch = ((Number) root.getOrDefault("epoch", 0L)).longValue();
            Object snaps = root.get("snapshots");
            Map<String, DigestSnapshot> snapshots = new HashMap<>();
            if (snaps instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) snaps).entrySet()) {
                    if (e.getValue() instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) e.getValue();
                        snapshots.put(e.getKey(), new DigestSnapshot(
                            e.getKey(),
                            (String) m.get("digestText"),
                            (String) m.get("schema"),
                            ((Number) m.getOrDefault("count", 0L)).longValue(),
                            ((Number) m.getOrDefault("timer", 0L)).longValue(),
                            ((Number) m.getOrDefault("lockTime", 0L)).longValue(),
                            ((Number) m.getOrDefault("rowsExamined", 0L)).longValue(),
                            ((Number) m.getOrDefault("rowsSent", 0L)).longValue(),
                            ((Number) m.getOrDefault("firstSeenMillis", 0L)).longValue(),
                            ((Number) m.getOrDefault("lastSeenMillis", 0L)).longValue()
                        ));
                    }
                }
            }
            return new CursorState(epoch, snapshots);
        } catch (Exception e) {
            return new CursorState(0L, Map.of());
        }
    }

    private String buildCursor(Map<String, DigestSnapshot> snapshots, long epoch) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("epoch", epoch);
        Map<String, Object> snaps = new LinkedHashMap<>();
        for (Map.Entry<String, DigestSnapshot> e : snapshots.entrySet()) {
            DigestSnapshot s = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("digestText", s.digestText());
            m.put("schema", s.schema());
            m.put("count", s.count());
            m.put("timer", s.timer());
            m.put("lockTime", s.lockTime());
            m.put("rowsExamined", s.rowsExamined());
            m.put("rowsSent", s.rowsSent());
            m.put("firstSeenMillis", s.firstSeenMillis());
            m.put("lastSeenMillis", s.lastSeenMillis());
            snaps.put(e.getKey(), m);
        }
        root.put("snapshots", snaps);
        try {
            return OM.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Instant latestLastSeen(Map<String, DigestSnapshot> map) {
        long max = 0L;
        for (DigestSnapshot s : map.values()) {
            if (s.lastSeenMillis() > max) {
                max = s.lastSeenMillis();
            }
        }
        return max == 0L ? Instant.now() : Instant.ofEpochMilli(max);
    }

    private String sanitize(String s) {
        if (s == null) return null;
        return SECRET_LIT.matcher(s).replaceAll("$1=?");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }

    private record CursorState(long epoch, Map<String, DigestSnapshot> snapshots) {
    }
}
