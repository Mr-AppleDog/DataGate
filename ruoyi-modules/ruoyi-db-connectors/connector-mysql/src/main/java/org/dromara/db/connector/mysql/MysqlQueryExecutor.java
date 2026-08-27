package org.dromara.db.connector.mysql;

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
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
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
 * MySQL 查询执行器（QRY-201 / docs/06 §4 step 10-13、§6.4、§11）。
 *
 * <p>受控流式执行：接收已授权的 {@link ExecutionPlan} 与编排器组装的 {@link ConnectionContext}
 *（凭据 + 原始可执行语句），建立 HikariCP 小池连接，设只读事务/超时/会话保护，
 * 游标流式吐行（行/字节/单元格上限与截断、二进制摘要），不持久保存结果正文，
 * 结束统一 ROLLBACK 并清理会话状态。</p>
 *
 * <p><b>接缝</b>（ADR-008）：凭据与原始语句经 {@link ConnectionContext} 参数流入，
 * 由 db-executor 编排器在执行前解析组装（数据源→ConnectionProfile、凭据→SecretValue、
 * 原始语句→用户提交经解析校验）。执行器<b>不再自建 resolver 端口</b>，
 * 只管引擎细节（JDBC/会话保护/流式脱敏）。</p>
 *
 * <p><b>失败关闭 + 纵深防御</b>：执行器假设所有输入不可信——计划过期/凭据已销毁→REJECTED；
 * 并<b>独立重新解析原始语句</b>校验只读与单语句，即便编排者被攻陷也无法将 DML/DDL 经执行器落地
 * （AGENTS.md §6：先执行再判断权限禁止；SQL 解析失败失败关闭）。</p>
 *
 * <p><b>凭据生命周期</b>：{@link ConnectionContext#secret()} 使用后立即销毁；连接池按
 * dataSourceId×username 隔离（凭据版本由编排器管理；同 dataSourceId 切换 username 时淘汰旧池），
 * 符合 docs/02 §8.3"连接池由执行器按数据源×用途建立"。HikariCP 内部需保留密码以重建连接
 * （JDBC 池化固有），随池关闭而清除，不进日志/异常/缓存。</p>
 *
 * <p><b>审计</b>：不记 SQL 参数/结果正文，只返回 {@link ExecutionResultMeta}
 *（executionNo/状态/耗时/行数/字节数/截断/错误码）。</p>
 *
 * @author DataGate
 */
public class MysqlQueryExecutor implements QueryExecutor {

    /** 单元格硬上限 1 MB（docs/06 §11）。 */
    private static final int CELL_LIMIT = 1 << 20;

    private final QueryParser parser;
    /** 连接池缓存：dataSourceId:username → HikariDataSource。 */
    private final ConcurrentMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    /** dataSourceId → 当前版本池 key（用于切换 username 时淘汰旧池）。 */
    private final ConcurrentMap<Long, String> dsVersion = new ConcurrentHashMap<>();
    /** executionNo → 运行中的 Statement（供跨节点/线程取消）。 */
    private final ConcurrentMap<String, Statement> running = new ConcurrentHashMap<>();

    public MysqlQueryExecutor(QueryParser parser) {
        this.parser = parser;
    }

    @Override
    public ExecutionResultMeta execute(ExecutionPlan plan, ConnectionContext ctx, RowCallback callback) {
        long start = System.nanoTime();
        String executionNo = "mysql-" + UUID.randomUUID();

        // 1. 计划校验（失败关闭）
        if (plan == null) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PARSE_FAILED);
        }
        if (plan.isExpired(Instant.now())) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PLAN_EXPIRED);
        }

        // 2. 上下文校验（失败关闭）—— ConnectionContext 的 compact constructor 已校验非 null/非空，
        //    但纵深防御：ctx 为 null / secret 已销毁 / originalStatement 为空均拒绝
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

        // 3. 纵深防御：独立重新解析原始语句，校验只读与单语句（不信任编排者的分类）
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
            conn.setAutoCommit(false);
            try {
                conn.setReadOnly(true);
            } catch (SQLException ignored) {
                // 部分驱动/版本在事务中拒绝 setReadOnly，后续只读事务仍由 SQL 性质保证
            }
            if (plan.databaseName() != null && !plan.databaseName().isBlank()) {
                try {
                    conn.setCatalog(plan.databaseName());
                } catch (SQLException ignored) {
                    // catalog 设置失败不致命，由连接默认库决定
                }
            }
            stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            int timeoutSec = (int) Math.min(Math.max(plan.maxExecutionSeconds(), 1), Integer.MAX_VALUE);
            stmt.setQueryTimeout(timeoutSec);
            running.put(executionNo, stmt);
            // 服务端 SELECT 最大执行时间（docs/06 §6.4），毫秒
            try {
                stmt.execute("SET SESSION max_execution_time=" + (timeoutSec * 1000L));
            } catch (SQLException ignored) {
                // 非纯 SELECT 或不支持时忽略，JDBC queryTimeout 仍生效
            }
            boolean hasRs = stmt.execute(ctx.originalStatement());
            if (!hasRs) {
                // 只读校验通过却无结果集（如安全 SHOW 的部分形态）——不产出行
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
                    // 回滚失败：连接状态不可信，HikariCP 会驱逐该连接
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

    /** 关闭连接池（集成者/容器停机调用，释放连接与残留凭据）。 */
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

    private HikariDataSource poolFor(Long dataSourceId, ConnectionContext ctx) {
        ConnectionProfile p = ctx.profile();
        String user = (p.username() == null || p.username().isBlank()) ? "default" : p.username();
        String key = dataSourceId + ":" + user;
        HikariDataSource existing = pools.get(key);
        if (existing != null) {
            return existing;
        }
        // 同 dataSourceId 切换 username：关闭旧池（docs/02 §8.3）
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
        cfg.setPoolName("datagate-mysql-" + dataSourceId + "-" + user);
        cfg.setMaximumPoolSize(5);
        cfg.setReadOnly(true);
        cfg.setAutoCommit(false);
        cfg.setConnectionTimeout(Math.max(p.connectTimeout().toMillis(), 1000L));
        cfg.setIdleTimeout(600_000L);
        cfg.setMaxLifetime(1_800_000L);
        return new HikariDataSource(cfg);
    }

    private String buildJdbcUrl(ConnectionProfile p) {
        StringBuilder url = new StringBuilder("jdbc:mysql://")
            .append(p.host()).append(':').append(p.port()).append('/');
        if (p.defaultDatabase() != null && !p.defaultDatabase().isBlank()) {
            url.append(p.defaultDatabase());
        }
        url.append("?connectTimeout=").append(p.connectTimeout().toMillis())
            .append("&socketTimeout=").append(p.socketTimeout().toMillis());
        applyTls(url, p.tlsMode());
        return url.toString();
    }

    private void applyTls(StringBuilder url, TlsMode tlsMode) {
        switch (tlsMode) {
            case DISABLE -> url.append("&useSSL=false");
            case PREFER -> url.append("&useSSL=true&requireSSL=false");
            case REQUIRE -> url.append("&useSSL=true&requireSSL=true");
            case VERIFY_CA, FULL -> url.append("&useSSL=true&requireSSL=true&verifyServerCertificate=true");
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
                    String truncatedVal = new String(
                        truncateToBytes(v, CELL_LIMIT), StandardCharsets.UTF_8);
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
        return t.contains("BLOB") || t.equals("BINARY") || t.equals("VARBINARY")
            || t.equals("GEOMETRY") || t.equals("BIT");
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
        if (t.contains("INT") || t.contains("DECIMAL") || t.contains("NUMERIC")
            || t.contains("FLOAT") || t.contains("DOUBLE") || t.contains("BIT")) {
            return "number";
        }
        if (t.contains("DATE") || t.contains("TIME") || t.contains("YEAR")) {
            return "timestamp";
        }
        return "text";
    }

    private static ExecutionStatus classifySqlException(SQLException e) {
        String sqlState = e.getSQLState();
        int ec = e.getErrorCode();
        // MySQL 取消：SQLState 70100 / ERROR 1317
        if ("70100".equals(sqlState) || ec == 1317) {
            return ExecutionStatus.CANCELED;
        }
        // JDBC queryTimeout
        if (ec == 0 && (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"))) {
            return ExecutionStatus.TIMED_OUT;
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
