package org.dromara.db.connector.postgresql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.db.connector.postgresql.support.PostgresStatementSnapshotDiffer;
import org.dromara.db.connector.postgresql.support.PostgresStatementSnapshotDiffer.PgDiff;
import org.dromara.db.connector.postgresql.support.PostgresStatementSnapshotDiffer.PgSnapshot;
import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.error.DbErrorCode;
import org.postgresql.util.PSQLException;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.SlowQueryProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * PostgreSQL 慢查询采集器（docs/07 §4.3）。
 *
 * pg_stat_statements 摘要差值：保存 calls/total_exec_time/rows 快照，差值生成时间桶，标 AGGREGATED。
 * 扩展未安装时抛 RESOURCE_CAPABILITY_UNSUPPORTED（能力不可用，不把零数据视为健康，docs/07 §4.3）。
 * stats_reset 或计数回退视为重置，重建基线不产出负指标。
 *
 * nativeFingerprint=queryid；query 已由 PG 归一化（去常量）；portableFingerprint=SHA-256(敏感清理后 query)；
 * parserVersion=pg-pg_stat_statements；durationMicros=diff.total_exec_time(ms*1000)。
 *
 * @author DataGate
 */
public class PostgresqlSlowQueryProvider implements SlowQueryProvider {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String PARSER_VERSION = "pg-pg_stat_statements";

    private static final Pattern SECRET_LIT = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|token|authorization)\\s*[=:]\\s*\\S+");

    private static final String SQL_STATS =
        "SELECT userid::bigint, dbid::bigint, queryid::bigint, query, calls::bigint, " +
        "total_exec_time, rows::bigint " +
        "FROM pg_stat_statements WHERE queryid IS NOT NULL " +
        "ORDER BY total_exec_time DESC LIMIT ?";
    private static final String SQL_EXT =
        "SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')";
    private static final String SQL_DB = "SELECT oid::bigint, datname FROM pg_database";

    private final PostgresqlConnector connector;

    public PostgresqlSlowQueryProvider(PostgresqlConnector connector) {
        this.connector = connector;
    }

    @Override
    public SlowQueryPage pull(ConnectionProfile profile, SecretValue secret,
                              CollectorCursor cursor, int limit) {
        CursorState state = parseCursor(cursor);
        long epoch = state.epoch + 1;
        Instant now = Instant.now();
        Map<String, PgSnapshot> current = new LinkedHashMap<>();
        Map<Long, String> dbNames = new HashMap<>();

        Properties props = new Properties();
        props.setProperty("user", profile.username() == null ? "" : profile.username());
        secret.useSecret(chars -> props.setProperty("password", new String(chars)));
        try (Connection conn = DriverManager.getConnection(connector.buildJdbcUrl(profile), props)) {
            // 扩展检测：未安装明确能力不可用，不视零数据为健康（docs/07 §4.3）
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SQL_EXT)) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED,
                        "pg_stat_statements 扩展未安装");
                }
            }
            // 库名映射 dbid → datname
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(SQL_DB)) {
                while (rs.next()) {
                    dbNames.put(rs.getLong(1), rs.getString(2));
                }
            }
            // 摘要快照
            try (PreparedStatement ps = conn.prepareStatement(SQL_STATS)) {
                ps.setInt(1, Math.max(limit, 1));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long userid = rs.getLong(1);
                        long dbid = rs.getLong(2);
                        long queryid = rs.getLong(3);
                        String query = rs.getString(4);
                        long calls = rs.getLong(5);
                        double totalExecTimeMs = rs.getDouble(6);
                        long rows = rs.getLong(7);
                        String key = queryid + ":" + dbid + ":" + userid;
                        String dbName = dbNames.getOrDefault(dbid, "dbid:" + dbid);
                        current.put(key, new PgSnapshot(key, query, dbName,
                            calls, (long) (totalExecTimeMs * 1000), rows));
                    }
                }
            }
        } catch (DbServiceException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (e instanceof PSQLException
                || msg.contains("pg_stat_statements")
                || msg.contains("shared_preload_libraries")) {
                throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED,
                    "pg_stat_statements 不可用（扩展未安装或未 preload）");
            }
            throw new DbServiceException(DbErrorCode.QUERY_ENGINE_UNAVAILABLE,
                "采集连接失败: " + e.getClass().getSimpleName());
        } finally {
            props.remove("password");
        }

        List<PgDiff> diffs = PostgresStatementSnapshotDiffer.diff(current, state.snapshots);
        List<SlowQueryRecord> records = new ArrayList<>();
        for (PgDiff d : diffs) {
            if (d.reset() || d.calls() <= 0) {
                continue;
            }
            String normalized = sanitize(d.query());
            String fingerprint = sha256(normalized);
            String sourceKey = "agg:" + d.key() + ":" + epoch;
            records.add(new SlowQueryRecord(
                sourceKey,
                d.key(),
                null,
                "POSTGRESQL",
                d.databaseName(),
                fingerprint,
                d.key(),
                PARSER_VERSION,
                normalized,
                null,
                now,
                now,
                d.totalExecTimeMicros(),
                null,
                null,
                d.rows(),
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

        String nextCursorJson = buildCursor(current, epoch);
        CollectorCursor nextCursor = new CollectorCursor(
            cursor == null ? null : cursor.sourceId(),
            cursor == null ? "default" : cursor.partitionKey(),
            nextCursorJson,
            now,
            cursor == null ? 0L : cursor.version()
        );
        return new SlowQueryPage(records, nextCursor);
    }

    private CursorState parseCursor(CollectorCursor cursor) {
        if (cursor == null || cursor.cursor() == null || cursor.cursor().isBlank()) {
            return new CursorState(0L, Map.of());
        }
        try {
            Map<String, Object> root = OM.readValue(cursor.cursor(), new TypeReference<Map<String, Object>>() {});
            long epoch = ((Number) root.getOrDefault("epoch", 0L)).longValue();
            Object snaps = root.get("snapshots");
            Map<String, PgSnapshot> snapshots = new HashMap<>();
            if (snaps instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) snaps).entrySet()) {
                    if (e.getValue() instanceof Map) {
                        Map<String, Object> m = (Map<String, Object>) e.getValue();
                        snapshots.put(e.getKey(), new PgSnapshot(
                            e.getKey(),
                            (String) m.get("query"),
                            (String) m.get("databaseName"),
                            ((Number) m.getOrDefault("calls", 0L)).longValue(),
                            ((Number) m.getOrDefault("totalExecTimeMicros", 0L)).longValue(),
                            ((Number) m.getOrDefault("rows", 0L)).longValue()
                        ));
                    }
                }
            }
            return new CursorState(epoch, snapshots);
        } catch (Exception e) {
            return new CursorState(0L, Map.of());
        }
    }

    private String buildCursor(Map<String, PgSnapshot> snapshots, long epoch) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("epoch", epoch);
        Map<String, Object> snaps = new LinkedHashMap<>();
        for (Map.Entry<String, PgSnapshot> e : snapshots.entrySet()) {
            PgSnapshot s = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("query", s.query());
            m.put("databaseName", s.databaseName());
            m.put("calls", s.calls());
            m.put("totalExecTimeMicros", s.totalExecTimeMicros());
            m.put("rows", s.rows());
            snaps.put(e.getKey(), m);
        }
        root.put("snapshots", snaps);
        try {
            return OM.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
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

    private record CursorState(long epoch, Map<String, PgSnapshot> snapshots) {
    }
}
