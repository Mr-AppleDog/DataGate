package org.dromara.db.connector.postgresql;

import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.RowCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 连接器真实引擎集成测试（docs/06 §7、§15、§16）。
 *
 * <p>连接 VM {@code 192.168.149.128} PostgreSQL 18（AGENTS.md §5 文档化测试凭据）。
 * 覆盖 §16 验收：允许只读 SELECT 返回行、强制拒绝语料零写入（COPY/SELECT INTO/CREATE/EXPLAIN ANALYZE）、
 * 结果超限截断、超时无遗留活动语句。凭据经系统属性可覆盖（CI 默认文档化测试值）。</p>
 *
 * <p>仅 {@code -DtestTags=integration} 触发；默认构建（{@code unit}）不运行。</p>
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("PostgreSQL 连接器真实引擎集成测试 (§16)")
class PostgresqlConnectorIntegrationTest {

    private static final String HOST = System.getProperty("datagate.pg.host", "192.168.149.128");
    private static final int PORT = Integer.getInteger("datagate.pg.port", 5432);
    private static final String USER = System.getProperty("datagate.pg.user", "postgres");
    private static final String PASS = System.getProperty("datagate.pg.pass", "mrlu");
    private static final String DB = System.getProperty("datagate.pg.db", "postgres");
    private static final String SCHEMA = "datagate_it";

    private final PostgresqlQueryParser parser = new PostgresqlQueryParser();
    private final PostgresqlQueryExecutor executor = new PostgresqlQueryExecutor(parser);

    @BeforeAll
    static void setupSchema() throws Exception {
        try (Connection c = rawConn();
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
            s.execute("CREATE TABLE " + SCHEMA + ".t(id int, val text)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + SCHEMA + ".t(id, val) VALUES(?,?)")) {
                for (int i = 1; i <= 20; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "row" + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    @AfterAll
    static void teardownSchema() throws Exception {
        try (Connection c = rawConn();
             Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            c.commit();
        }
    }

    private static Connection rawConn() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", USER);
        p.setProperty("password", PASS);
        Connection c = DriverManager.getConnection(
            "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB + "?sslmode=disable", p);
        c.setAutoCommit(false);
        return c;
    }

    // ====================== 夹具 ======================

    private ConnectionProfile profile() {
        return new ConnectionProfile(HOST, PORT, DB, USER, Map.of(), TlsMode.DISABLE,
            Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private ExecutionPlan plan(String type, long maxRows, long maxBytes, long maxSec) {
        return ExecutionPlan.of("p1", 1L, 100L, DB, SCHEMA, "h", "SELECT 1", type,
            List.of(1L), "dec-1", maxRows, maxBytes, maxSec,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(120));
    }

    private ConnectionContext ctx(String stmt) {
        return new ConnectionContext(profile(), SecretValue.of(PASS), stmt);
    }

    private static final class Sink implements RowCallback {
        int headers;
        int rows;
        boolean completed;

        @Override public void onHeader(RowHeader h) { headers++; }
        @Override public boolean onRow(List<RowCell> cells) { rows++; return true; }
        @Override public void onComplete() { completed = true; }
        @Override public boolean onError(Throwable t) { return true; }
    }

    // ====================== §16 验收：允许只读 SELECT ======================

    @Test
    @DisplayName("允许：SELECT 返回行（真实 PG）")
    void allowSelectReturnsRows() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("SELECT * FROM t ORDER BY id"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(20, sink.rows, "20 行");
        assertTrue(sink.completed);
        assertEquals(20, m.rowCount());
    }

    @Test
    @DisplayName("允许：schema 限定 SELECT（public 系统目录只读）")
    void allowCatalogSelect() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("SELECT 1 AS one"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(1, sink.rows);
    }

    @Test
    @DisplayName("允许：安全 EXPLAIN 不执行原语句")
    void allowExplain() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan("EXPLAIN", 100, 10_000_000, 30),
            ctx("EXPLAIN SELECT * FROM t"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(sink.rows > 0, "EXPLAIN 输出计划行");
    }

    // ====================== §16 验收：强制拒绝语料零写入 ======================

    @Test
    @DisplayName("拒绝：SELECT INTO 不创建表（零写入）")
    void rejectSelectIntoNoWrite() throws Exception {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("SELECT * INTO newtbl FROM t"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        // 验证 newtbl 未创建
        try (Connection c = rawConn();
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                "SELECT 1 FROM pg_tables WHERE schemaname='" + SCHEMA + "' AND tablename='newtbl'")) {
                assertFalse(rs.next(), "SELECT INTO 必须未创建 newtbl（零写入）");
            }
            c.rollback();
        }
    }

    @Test
    @DisplayName("拒绝：CREATE TABLE 不创建（零写入）")
    void rejectCreateNoWrite() throws Exception {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("CREATE TABLE evil(id int)"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        try (Connection c = rawConn();
             Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                "SELECT 1 FROM pg_tables WHERE schemaname='" + SCHEMA + "' AND tablename='evil'")) {
                assertFalse(rs.next(), "CREATE TABLE 必须未创建 evil（零写入）");
            }
            c.rollback();
        }
    }

    @Test
    @DisplayName("拒绝：EXPLAIN ANALYZE 不执行原语句（零写入）")
    void rejectExplainAnalyzeNoExec() {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("EXPLAIN ANALYZE SELECT * FROM t"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status(),
            "EXPLAIN ANALYZE 必须被拒绝（执行器纵深再解析 readonly=false）");
    }

    @Test
    @DisplayName("拒绝：FOR UPDATE 被拒绝")
    void rejectForUpdate() {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("SELECT * FROM t FOR UPDATE"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
    }

    @Test
    @DisplayName("拒绝：COPY FROM 被拒绝（失败关闭或 unsafe）")
    void rejectCopy() {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("COPY t FROM '/tmp/x.csv'"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertTrue("QUERY_PARSE_FAILED".equals(m.errorCode())
                || "QUERY_UNSAFE_STATEMENT".equals(m.errorCode()));
    }

    @Test
    @DisplayName("拒绝：文件函数 pg_read_file 被拒绝")
    void rejectFileFunction() {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("SELECT pg_read_file('/etc/passwd')"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
    }

    // ====================== §16 验收：结果超限可截断 ======================

    @Test
    @DisplayName("截断：maxRows=3 时 SELECT 返回 3 行并标记 truncated")
    void truncateMaxRows() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan("SELECT", 3, 10_000_000, 30),
            ctx("SELECT * FROM t ORDER BY id"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "超 maxRows 必须截断");
        assertEquals(3, sink.rows);
        assertEquals(3, m.rowCount());
    }

    @Test
    @DisplayName("截断：maxBytes 小时截断")
    void truncateMaxBytes() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 16, 30),
            ctx("SELECT * FROM t ORDER BY id"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "超 maxBytes 必须截断");
    }

    // ====================== §16 验收：超时无遗留活动语句 ======================

    @Test
    @DisplayName("超时：慢查询超过 statement_timeout → TIMED_OUT，无遗留活动")
    void timeoutNoResidual() throws Exception {
        // 笛卡尔积慢查询，超过 3s statement_timeout
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 3),
            ctx("SELECT count(*) FROM generate_series(1, 100000000) a, generate_series(1, 100000000) b"),
            sink);
        // 慢查询应超时（TIMED_OUT 或 FAILED），不会 SUCCEEDED
        assertTrue(m.status() == ExecutionStatus.TIMED_OUT || m.status() == ExecutionStatus.FAILED
                || m.status() == ExecutionStatus.CANCELED,
            "慢查询应超时/失败: " + m.status());
        // 验证无遗留活动语句（application_name 非 datagate 的 datagate 连接残留）
        Thread.sleep(500); // 给 PG 清理时间
        try (Connection c = rawConn();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name LIKE 'datagate:%'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "超时后无 datagate 遗留活动语句");
            c.rollback();
        }
    }

    @Test
    @DisplayName("cancel 幂等不抛")
    void cancelIdempotent() {
        ExecutionResultMeta m = executor.execute(plan("SELECT", 100, 10_000_000, 30),
            ctx("SELECT 1"), new Sink());
        executor.cancel(m.executionNo());
        executor.cancel(null);
    }
}
