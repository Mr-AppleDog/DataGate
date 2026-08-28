package org.dromara.db.connector.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.spi.ChangeExecutor;
import org.dromara.db.core.spi.QueryParser;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MySQL 变更执行器（docs/06 §13，M5-02）。
 *
 * <p>专用变更账号（CredentialPurpose.CHANGE）独立连接池；allowMultiQueries 逐语句结果迭代；
 * 设置锁等待与执行超时；记录每条语句状态/影响行数。DDL 自动提交（引擎特性），
 * 不做自动回滚承诺（docs/06 §13）。纵深防御：重新解析校验动作必须为 CHANGE_DML/CHANGE_DDL。</p>
 *
 * @author DataGate
 */
public class MysqlChangeExecutor implements ChangeExecutor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MysqlChangeExecutor.class);

    private final MysqlConnector connector;
    private final QueryParser parser;
    private final ConcurrentMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> dsVersion = new ConcurrentHashMap<>();

    public MysqlChangeExecutor(MysqlConnector connector) {
        this.connector = connector;
        this.parser = connector.queryParser();
    }

    @Override
    public ChangeResult execute(ChangeExecutionRequest req, ConnectionContext ctx) {
        long start = System.nanoTime();
        String executionNo = "mysql-change-" + UUID.randomUUID();
        if (ctx == null || ctx.secret() == null || ctx.secret().isDestroyed()
            || ctx.originalStatement() == null || ctx.originalStatement().isBlank()) {
            ctx.secret().destroy();
            return ChangeResult.failed(executionNo, DbErrorCode.QUERY_PARSE_FAILED.name(), ms(start));
        }
        // 纵深防御：重新解析校验动作（CHANGE_DML/CHANGE_DDL），拒绝只读查询经此路径
        List<ParsedStatement> parsed;
        try {
            parsed = parser.parse(ctx.originalStatement());
        } catch (RuntimeException e) {
            ctx.secret().destroy();
            return ChangeResult.failed(executionNo, DbErrorCode.QUERY_PARSE_FAILED.name(), ms(start));
        }
        if (parsed.isEmpty()) {
            ctx.secret().destroy();
            return ChangeResult.failed(executionNo, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), ms(start));
        }
        for (ParsedStatement ps : parsed) {
            if (ps.requiredAction() != DbAction.CHANGE_DML && ps.requiredAction() != DbAction.CHANGE_DDL) {
                ctx.secret().destroy();
                return ChangeResult.failed(executionNo, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), ms(start));
            }
        }

        HikariDataSource ds = poolFor(req.dataSourceId(), ctx);
        long totalAffected = 0;
        List<String> results = new ArrayList<>();
        ExecutionStatus finalStatus = ExecutionStatus.SUCCEEDED;
        String errorCode = null;
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = ds.getConnection();
            conn.setAutoCommit(true); // DDL 自动提交；DML 每语句独立提交（P0，不做自动回滚）
            if (req.databaseName() != null && !req.databaseName().isBlank()) {
                try { conn.setCatalog(req.databaseName()); } catch (SQLException ignored) { }
            }
            stmt = conn.createStatement();
            int timeoutSec = (int) Math.min(Math.max(req.maxExecutionSeconds(), 1), Integer.MAX_VALUE);
            try { stmt.execute("SET SESSION lock_wait_timeout=" + (timeoutSec)); } catch (SQLException ignored) { }
            boolean hasRs = stmt.execute(ctx.originalStatement()); // allowMultiQueries 逐语句
            int idx = 0;
            do {
                int uc = stmt.getUpdateCount();
                if (uc >= 0) {
                    totalAffected += uc;
                    results.add(stmtResult(idx, "SUCCEEDED", uc, null, 0));
                }
                idx++;
            } while (stmt.getMoreResults() || stmt.getUpdateCount() != -1);
        } catch (SQLException e) {
            finalStatus = ExecutionStatus.FAILED;
            errorCode = DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name();
            results.add(stmtResult(results.size(), "FAILED", 0, errorCode, 0));
            log.warn("变更执行异常 jobId={}", req.jobId(), e);
        } finally {
            if (stmt != null) { try { stmt.close(); } catch (SQLException ignored) { } }
            if (conn != null) { try { conn.close(); } catch (SQLException ignored) { } }
            ctx.secret().destroy();
        }
        return new ChangeResult(executionNo, finalStatus, totalAffected, resultsJson(results), errorCode, ms(start));
    }

    /** 关闭池（停机释放连接与残留凭据） */
    public void shutdown() {
        pools.values().forEach(p -> { try { p.close(); } catch (RuntimeException ignored) { } });
        pools.clear();
        dsVersion.clear();
    }

    // ====================== 内部 ======================

    private HikariDataSource poolFor(Long dataSourceId, ConnectionContext ctx) {
        ConnectionProfile p = ctx.profile();
        String user = (p.username() == null || p.username().isBlank()) ? "change" : p.username();
        String key = dataSourceId + ":change:" + user;
        HikariDataSource existing = pools.get(key);
        if (existing != null) {
            return existing;
        }
        String prev = dsVersion.put(dataSourceId, key);
        if (prev != null && !prev.equals(key)) {
            HikariDataSource old = pools.remove(prev);
            if (old != null) old.close();
        }
        HikariDataSource created = buildPool(ctx, dataSourceId, user);
        HikariDataSource raced = pools.putIfAbsent(key, created);
        if (raced != null) { created.close(); return raced; }
        return created;
    }

    private HikariDataSource buildPool(ConnectionContext ctx, Long dataSourceId, String user) {
        ConnectionProfile p = ctx.profile();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildChangeJdbcUrl(p));
        cfg.setUsername(p.username());
        ctx.secret().useSecret(chars -> cfg.setPassword(new String(chars)));
        cfg.setPoolName("datagate-mysql-change-" + dataSourceId + "-" + user);
        cfg.setMaximumPoolSize(3);
        cfg.setAutoCommit(true);
        cfg.setConnectionTimeout(Math.max(p.connectTimeout().toMillis(), 1000L));
        cfg.setIdleTimeout(600_000L);
        cfg.setMaxLifetime(1_800_000L);
        return new HikariDataSource(cfg);
    }

    /** 变更 JDBC URL：allowMultiQueries=true 逐语句结果迭代；复用连接器 TLS。 */
    private String buildChangeJdbcUrl(ConnectionProfile p) {
        StringBuilder url = new StringBuilder("jdbc:mysql://")
            .append(p.host()).append(':').append(p.port()).append('/');
        if (p.defaultDatabase() != null && !p.defaultDatabase().isBlank()) {
            url.append(p.defaultDatabase());
        }
        url.append("?allowMultiQueries=true&connectTimeout=").append(p.connectTimeout().toMillis())
            .append("&socketTimeout=").append(p.socketTimeout().toMillis());
        TlsMode tls = p.tlsMode() == null ? TlsMode.PREFER : p.tlsMode();
        switch (tls) {
            case DISABLE -> url.append("&useSSL=false");
            case PREFER -> url.append("&useSSL=true&requireSSL=false");
            case REQUIRE -> url.append("&useSSL=true&requireSSL=true");
            case VERIFY_CA, FULL -> url.append("&useSSL=true&requireSSL=true&verifyServerCertificate=true");
        }
        return url.toString();
    }

    private static String stmtResult(int idx, String status, long affectedRows, String errorCode, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append('{').append('"').append("idx").append('"').append(':').append(idx);
        sb.append(',').append('"').append("status").append('"').append(':').append('"').append(status).append('"');
        sb.append(',').append('"').append("affectedRows").append('"').append(':').append(affectedRows);
        sb.append(',').append('"').append("errorCode").append('"').append(':');
        if (errorCode == null) {
            sb.append("null");
        } else {
            sb.append('"').append(errorCode).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String resultsJson(List<String> results) {
        return "[" + String.join(",", results) + "]";
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
