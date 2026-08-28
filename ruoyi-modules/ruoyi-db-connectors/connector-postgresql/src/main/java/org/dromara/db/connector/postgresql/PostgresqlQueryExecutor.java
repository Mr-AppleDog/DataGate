package org.dromara.db.connector.postgresql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.masking.MaskingApplier;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.spi.FieldMaskingEngine;
import org.dromara.db.core.spi.QueryExecutor;
import org.dromara.db.core.spi.QueryParser;
import org.dromara.db.core.spi.RowCallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PostgreSQL 查询执行器（QRY-201 / docs/06 §4 step 10-13、§7.4、§11）。
 *
 * <p>受控流式执行：接收已授权的 {@link ExecutionPlan} 与编排器组装的 {@link ConnectionContext}
 *（凭据 + 原始可执行语句），建立 HikariCP 小池连接，设只读事务/超时/search_path/application_name
 * 会话保护，游标流式吐行（行/字节/单元格上限与截断、二进制摘要），不持久保存结果正文，
 * 结束统一 ROLLBACK 并 RESET ALL 清理会话状态。</p>
 *
 * <p><b>接缝</b>（ADR-008）：凭据与原始语句经 {@link ConnectionContext} 参数流入，执行器不再
 * 自建 resolver 端口，只管引擎细节（JDBC/会话保护/流式脱敏）。</p>
 *
 * <p><b>失败关闭 + 纵深防御</b>：执行器假设所有输入不可信——计划过期/凭据已销毁→REJECTED；
 * 并独立重新解析原始语句校验只读与单语句（AGENTS.md §6：先执行再判断权限禁止）。</p>
 *
 * <p><b>PG 会话保护</b>（docs/06 §7.4）：BEGIN READ ONLY；SET LOCAL statement_timeout、
 * lock_timeout、idle_in_transaction_session_timeout；search_path 固定为授权模式并含 pg_catalog，
 * 不接受未校验的原始 search_path；application_name 写入 executionId 便于定位与取消；
 * 结束 ROLLBACK + RESET ALL；失败销毁连接。</p>
 *
 * <p><b>审计</b>：不记 SQL 参数/结果正文，只返回 {@link ExecutionResultMeta}。</p>
 *
 * @author DataGate
 */
public class PostgresqlQueryExecutor implements QueryExecutor {

    /** 单元格硬上限 1 MB（docs/06 §11）。 */
    private static final int CELL_LIMIT = 1 << 20;

    private final QueryParser parser;
    /** 服务端流式脱敏引擎（docs/06 §11、M5-05c） */
    private final FieldMaskingEngine maskingEngine = new org.dromara.db.core.masking.DefaultFieldMaskingEngine();
    /** 连接池缓存：dataSourceId:username → HikariDataSource。 */
    private final ConcurrentMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** dataSourceId → 当前版本池 key。 */
    private final ConcurrentMap<Long, String> dsVersion = new ConcurrentHashMap<>();
    /** executionNo → 运行中的 Statement（供取消）。 */
    private final ConcurrentMap<String, Statement> running = new ConcurrentHashMap<>();

    public PostgresqlQueryExecutor(QueryParser parser) {
        this.parser = parser;
    }

    @Override
    public ExecutionResultMeta execute(ExecutionPlan plan, ConnectionContext ctx, RowCallback callback) {
        long start = System.nanoTime();
        String executionNo = "postgresql-" + UUID.randomUUID();

        // 1. 计划校验（失败关闭）
        if (plan == null) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PARSE_FAILED);
        }
        if (plan.isExpired(Instant.now())) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PLAN_EXPIRED);
        }

        // 2. 上下文校验（失败关闭）
        if (ctx == null) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_ENGINE_UNAVAILABLE);
        }
        if (ctx.profile() == null || ctx.secret() == null || ctx.secret().isDestroyed()) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_ENGINE_UNAVAILABLE);
        }
        if (ctx.originalStatement() == null || ctx.originalStatement().isBlank()) {
            ctx.secret().destroy();
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PARSE_FAILED);
        }

        // 3. 纵深防御：独立重新解析原始语句，校验只读与单语句
        if (parser != null) {
            List<ParsedStatement> parsed;
            try {
                parsed = parser.parse(ctx.originalStatement());
            } catch (RuntimeException e) {
                ctx.secret().destroy();
                return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                    DbErrorCode.QUERY_PARSE_FAILED);
            }
            if (parsed.size() != 1) {
                ctx.secret().destroy();
                return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                    DbErrorCode.QUERY_LIMIT_EXCEEDED);
            }
            ParsedStatement ps = parsed.get(0);
            if (!ps.readonly() || !isAllowedReadonlyAction(ps.requiredAction())) {
                ctx.secret().destroy();
                return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                    DbErrorCode.QUERY_UNSAFE_STATEMENT);
            }
        }

        // 4. 执行
        HikariDataSource ds = poolFor(plan.dataSourceId(), ctx);
        Connection conn = null;
        Statement stmt = null;
        long rows = 0;
        long bytes = 0;
        boolean truncated = false;
        try {
            conn = ds.getConnection();
            // PG 会话保护（docs/06 §7.4）：只读事务
            conn.setAutoCommit(false);
            try {
                conn.setReadOnly(true);
            } catch (SQLException ignored) {
                // 部分 PG 驱动版本在事务中 setReadOnly 由 BEGIN READ ONLY 保证
            }
            // search_path 固定（含 pg_catalog），不接受未校验的原始 search_path（docs/06 §7.4）
            String safeSchema = safeSearchPath(plan);
            try (Statement guard = conn.createStatement()) {
                guard.execute("SET LOCAL search_path = " + safeSchema);
                int timeoutSec = (int) Math.min(Math.max(plan.maxExecutionSeconds(), 1), Integer.MAX_VALUE);
                // SET LOCAL 会话保护参数（docs/06 §7.4）
                guard.execute("SET LOCAL statement_timeout = " + (timeoutSec * 1000L));
                guard.execute("SET LOCAL lock_timeout = " + (timeoutSec * 1000L));
                guard.execute("SET LOCAL idle_in_transaction_session_timeout = "
                    + (timeoutSec * 1000L));
                // application_name 写入 executionId，便于定位与取消（docs/06 §7.4）
                guard.execute("SET LOCAL application_name = 'datagate:" + executionNo + "'");
                // BEGIN READ ONLY（双保险，部分驱动 setReadOnly 已隐式）
                guard.execute("BEGIN READ ONLY");
            } catch (SQLException ignored) {
                // 上述任意一项失败：会话不可信，销毁连接而非归还（docs/06 §7.4）
                try {
                    conn.close();
                } catch (SQLException ignored2) {
                    // best effort
                }
                conn = null;
                ctx.secret().destroy();
                return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                    DbErrorCode.QUERY_ENGINE_UNAVAILABLE);
            }
            stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            int timeoutSec = (int) Math.min(Math.max(plan.maxExecutionSeconds(), 1), Integer.MAX_VALUE);
            stmt.setQueryTimeout(timeoutSec);
            running.put(executionNo, stmt);
            boolean hasRs = stmt.execute(ctx.originalStatement());
            if (!hasRs) {
                // 只读校验通过却无结果集——不产出行
                callback.onComplete();
                return meta(executionNo, ExecutionStatus.SUCCEEDED, start, 0, 0, false, null);
            }
            try (ResultSet rs = stmt.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                callback.onHeader(buildHeader(md, n));
                while (rs.next()) {
                    if (rows + 1 > plan.maxRows()) {
                        truncated = true;
                        break;
                    }
                    List<RowCell> cells = buildRow(rs, md, n);
                    cells = applyMasking(cells, md, n, plan);
                    long rowBytes = estimateBytes(cells);
                    bytes += rowBytes;
                    boolean cont = callback.onRow(cells);
                    rows++;
                    if (!cont) {
                        truncated = true;
                        break;
                    }
                    if (bytes > plan.maxBytes()) {
                        truncated = true;
                        break;
                    }
                }
            }
            callback.onComplete();
            return meta(executionNo, ExecutionStatus.SUCCEEDED, start, rows, bytes, truncated, null);
        } catch (SQLException e) {
            ExecutionStatus st = classifySqlException(e);
            DbErrorCode code = switch (st) {
                case TIMED_OUT -> DbErrorCode.QUERY_TIMEOUT;
                case CANCELED -> DbErrorCode.QUERY_CANCELED;
                default -> DbErrorCode.QUERY_ENGINE_UNAVAILABLE;
            };
            boolean handled = callback.onError(e);
            return meta(executionNo, handled ? ExecutionStatus.FAILED : st, start, rows, bytes, truncated,
                handled ? null : code);
        } catch (RuntimeException e) {
            boolean handled = callback.onError(e);
            return meta(executionNo, ExecutionStatus.FAILED, start, rows, bytes, truncated,
                handled ? null : DbErrorCode.INTERNAL_ERROR);
        } finally {
            running.remove(executionNo);
            closeQuiet(stmt);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // 回滚失败：连接状态不可信，HikariCP 驱逐该连接
                }
                // RESET ALL 清理会话状态（docs/06 §7.4）
                try (Statement reset = conn.createStatement()) {
                    reset.execute("RESET ALL");
                } catch (SQLException ignored) {
                    // RESET 失败：连接不可信
                }
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // 归还失败由池处理
                }
            }
            ctx.secret().destroy();
        }
    }

    @Override
    public void cancel(String executionNo) {
        if (executionNo == null) {
            return;
        }
        Statement s = running.get(executionNo);
        if (s != null) {
            try {
                s.cancel();
            } catch (SQLException ignored) {
                // 取消幂等；已结束或失败均不向上抛
            }
        }
    }

    /** 关闭连接池（容器停机调用，释放连接与残留凭据）。 */
    public void shutdown() {
        for (HikariDataSource ds : pools.values()) {
            try {
                ds.close();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        pools.clear();
        dsVersion.clear();
    }

    // ====================== 内部 ======================

    private static boolean isAllowedReadonlyAction(DbAction a) {
        return a == DbAction.QUERY || a == DbAction.EXPLAIN || a == DbAction.METADATA_READ;
    }

    /**
     * 构建安全的 search_path：固定为用户获授权的库/模式并包含 pg_catalog，
     * 不接受未校验的原始 search_path（docs/06 §7.4）。
     */
    private static String safeSearchPath(ExecutionPlan plan) {
        String schema = plan.schemaName();
        String db = plan.databaseName();
        // 优先 schemaName，其次 databaseName 作为 schema 名（PG 库即顶层命名空间）
        String primary = (schema != null && !schema.isBlank()) ? schema
            : (db != null && !db.isBlank() ? db : "public");
        return sanitizeIdent(primary) + ", pg_catalog";
    }

    /** 标识符清洗：只允许字母数字下划线与点，防止 search_path 注入。 */
    private static String sanitizeIdent(String ident) {
        if (ident == null || ident.isBlank()) {
            return "public";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ident.length(); i++) {
            char c = ident.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "public" : sb.toString();
    }

    private HikariDataSource poolFor(Long dataSourceId, ConnectionContext ctx) {
        ConnectionProfile p = ctx.profile();
        String user = (p.username() == null || p.username().isBlank()) ? "default" : p.username();
        String key = dataSourceId + ":" + user;
        HikariDataSource existing = pools.get(key);
        if (existing != null) {
            return existing;
        }
        String prev = dsVersion.put(dataSourceId, key);
        if (prev != null && !prev.equals(key)) {
            HikariDataSource old = pools.remove(prev);
            if (old != null) {
                old.close();
            }
        }
        HikariDataSource created = buildPool(ctx, dataSourceId, user);
        HikariDataSource raced = pools.putIfAbsent(key, created);
        if (raced != null) {
            created.close();
            return raced;
        }
        return created;
    }

    private HikariDataSource buildPool(ConnectionContext ctx, Long dataSourceId, String user) {
        ConnectionProfile p = ctx.profile();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildJdbcUrl(p));
        cfg.setUsername(p.username());
        // 密码：HikariCP 需 String 重建连接（JDBC 池化固有），随池关闭清除
        ctx.secret().useSecret(chars -> cfg.setPassword(new String(chars)));
        cfg.setPoolName("datagate-postgresql-" + dataSourceId + "-" + user);
        cfg.setMaximumPoolSize(5);
        cfg.setReadOnly(true);
        cfg.setAutoCommit(false);
        cfg.setConnectionTimeout(Math.max(p.connectTimeout().toMillis(), 1000L));
        cfg.setIdleTimeout(600_000L);
        cfg.setMaxLifetime(1_800_000L);
        // PG 连接健康检查（无副作用轻量语句，docs/06 §10.1）
        cfg.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(cfg);
    }

    private String buildJdbcUrl(ConnectionProfile p) {
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
            .append(p.host()).append(':').append(p.port());
        if (p.defaultDatabase() != null && !p.defaultDatabase().isBlank()) {
            url.append('/').append(p.defaultDatabase());
        }
        url.append("?connectTimeout=").append(p.connectTimeout().toSeconds())
            .append("&socketTimeout=").append(p.socketTimeout().toSeconds());
        applyTls(url, p.tlsMode());
        return url.toString();
    }

    private void applyTls(StringBuilder url, TlsMode tlsMode) {
        switch (tlsMode) {
            case DISABLE -> url.append("&sslmode=disable");
            case PREFER -> url.append("&sslmode=prefer");
            case REQUIRE -> url.append("&sslmode=require");
            case VERIFY_CA -> url.append("&sslmode=verify-ca");
            case FULL -> url.append("&sslmode=verify-full");
        }
    }

    private RowHeader buildHeader(ResultSetMetaData md, int n) throws SQLException {
        List<ColumnMeta> cols = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            String typeName = md.getColumnTypeName(i);
            cols.add(new ColumnMeta(md.getColumnLabel(i), typeName, displayType(typeName)));
        }
        return new RowHeader(cols);
    }

    /**
     * 服务端流式脱敏（docs/06 §11、M5-05c）：按 JDBC 列名 lineage 查列策略并掩码。
     * PG 驱动 getColumnName 返回列标签（别名时为别名），别名匹配不上策略 → 未知 → 在 MASKED 上下文 HIDDEN（安全兜底，防借名绕过）。
     */
    private List<RowCell> applyMasking(List<RowCell> cells, ResultSetMetaData md, int n, ExecutionPlan plan) {
        if (plan == null || n == 0) {
            return cells;
        }
        String[] tables = new String[n];
        String[] cols = new String[n];
        for (int i = 1; i <= n; i++) {
            try {
                tables[i - 1] = md.getTableName(i);
            } catch (SQLException ignored) {
                tables[i - 1] = "";
            }
            try {
                cols[i - 1] = md.getColumnName(i);
            } catch (SQLException ignored) {
                cols[i - 1] = "";
            }
        }
        return MaskingApplier.applyRow(cells, java.util.Arrays.asList(tables), java.util.Arrays.asList(cols), plan, maskingEngine);
    }

    private List<RowCell> buildRow(ResultSet rs, ResultSetMetaData md, int n) throws SQLException {
        List<RowCell> cells = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            String typeName = md.getColumnTypeName(i);
            if (rs.wasNull()) {
                cells.add(new RowCell(null, false, null));
                continue;
            }
            if (isBinary(typeName)) {
                byte[] b = rs.getBytes(i);
                cells.add(new RowCell(null, false, binarySummary(typeName, b)));
            } else {
                String v = rs.getString(i);
                if (v != null && v.getBytes(StandardCharsets.UTF_8).length > CELL_LIMIT) {
                    String truncatedVal = new String(truncateToBytes(v, CELL_LIMIT), StandardCharsets.UTF_8);
                    cells.add(new RowCell(truncatedVal, true, null));
                } else {
                    cells.add(new RowCell(v, false, null));
                }
            }
        }
        return cells;
    }

    private static boolean isBinary(String typeName) {
        if (typeName == null) {
            return false;
        }
        String t = typeName.toUpperCase();
        return t.equals("BYTEA") || t.equals("BLOB") || t.equals("BINARY")
            || t.equals("VARBINARY") || t.equals("GEOMETRY") || t.equals("BIT");
    }

    private static String binarySummary(String typeName, byte[] b) {
        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b == null ? new byte[0] : b);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            hash = sb.toString();
        } catch (Exception e) {
            hash = "unavailable";
        }
        return typeName + "/" + (b == null ? 0 : b.length) + "B/sha256:" + hash;
    }

    private static byte[] truncateToBytes(String s, int maxBytes) {
        byte[] all = s.getBytes(StandardCharsets.UTF_8);
        if (all.length <= maxBytes) {
            return all;
        }
        byte[] out = new byte[maxBytes];
        System.arraycopy(all, 0, out, 0, maxBytes);
        return out;
    }

    private static long estimateBytes(List<RowCell> cells) {
        long sum = 0;
        for (RowCell c : cells) {
            if (c.value() != null) {
                sum += c.value().getBytes(StandardCharsets.UTF_8).length;
            }
            if (c.binarySummary() != null) {
                sum += c.binarySummary().length();
            }
        }
        return sum;
    }

    private static String displayType(String typeName) {
        if (typeName == null) {
            return "text";
        }
        String t = typeName.toUpperCase();
        if (isBinary(t)) {
            return "binary";
        }
        if (t.contains("INT") || t.contains("SERIAL") || t.contains("DECIMAL") || t.contains("NUMERIC")
            || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("REAL")
            || t.equals("MONEY")) {
            return "number";
        }
        if (t.contains("DATE") || t.contains("TIME") || t.contains("TIMESTAMP")
            || t.contains("INTERVAL")) {
            return "timestamp";
        }
        if (t.equals("BOOLEAN") || t.equals("BOOL")) {
            return "boolean";
        }
        if (t.contains("JSON") || t.equals("XML")) {
            return "json";
        }
        return "text";
    }

    private static ExecutionStatus classifySqlException(SQLException e) {
        String sqlState = e.getSQLState();
        // PG 取消：SQLSTATE 57014（query_canceled）/ 57012
        if ("57014".equals(sqlState) || "57012".equals(sqlState)) {
            return ExecutionStatus.CANCELED;
        }
        // PG statement_timeout：SQLSTATE 57014（同取消码，靠 message 区分超时）
        if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
            return ExecutionStatus.TIMED_OUT;
        }
        // 只读事务违规（55006）或尝试写操作
        if ("55006".equals(sqlState) || "25006".equals(sqlState)) {
            return ExecutionStatus.FAILED;
        }
        Throwable cause = e.getCause();
        if (cause instanceof SQLException sqle) {
            return classifySqlException(sqle);
        }
        return ExecutionStatus.FAILED;
    }

    private static void closeQuiet(Statement stmt) {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    private static ExecutionResultMeta meta(String executionNo, ExecutionStatus status, long startNanos,
                                            long rows, long bytes, boolean truncated, DbErrorCode code) {
        long durationMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        return new ExecutionResultMeta(executionNo, status, durationMs, rows, bytes, truncated,
            code == null ? null : code.name());
    }
}
