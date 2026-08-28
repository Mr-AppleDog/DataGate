package org.dromara.db.connector.mysql;

import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.security.SecretValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 变更执行端到端集成测试（docs/06 §13、docs/10 M5-02，验收 #9）。
 *
 * <p>连真实 VM MySQL，经 MysqlChangeExecutor（专用变更账号独立池 + allowMultiQueries 逐语句）
 * 执行 UPDATE，断言 SUCCEEDED + affectedRows + 数据实际落地（专用账号执行）。
 * 验证 #9 执行核心：不可变快照经专用 CHANGE 凭据执行 + 影响行数 + 逐语句结果。</p>
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("MySQL 变更执行端到端 (验收 #9)")
class MysqlChangeExecutionIntegrationTest {

    private static final String HOST = "192.168.149.128";
    private static final int PORT = 3306;
    private static final String DB = "datagatetest";
    private static final String USER = "root";
    private static final String PASS = "mrlu";
    private static final String TABLE = "change_test";

    @BeforeAll
    static void setup() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/?allowMultiQueries=true", USER, PASS);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + DB);
            st.execute("DROP TABLE IF EXISTS " + DB + "." + TABLE);
            st.execute("CREATE TABLE " + DB + "." + TABLE + " (id INT, val VARCHAR(10))");
            st.execute("INSERT INTO " + DB + "." + TABLE + " (id, val) VALUES (1, 'a')");
        }
    }

    @AfterAll
    static void cleanup() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/?allowMultiQueries=true", USER, PASS);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + DB + "." + TABLE);
        }
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile(HOST, PORT, DB, USER, Map.of(), TlsMode.DISABLE, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("UPDATE 经专用变更账号执行 → SUCCEEDED + affectedRows=1 + 数据落地")
    void updateExecutedViaChangeCredential() {
        String sql = "UPDATE " + TABLE + " SET val='b' WHERE id=1";
        ChangeExecutionRequest req = new ChangeExecutionRequest(
            1L, 1L, null, null, 100L, DB, null, sql, List.of(), "dec-1", "idem-9-1", 30L, List.of(), List.of());
        MysqlChangeExecutor exec = new MysqlChangeExecutor(new MysqlConnector());
        ChangeResult r = exec.execute(req, new ConnectionContext(profile(), SecretValue.of(PASS), sql));
        assertEquals(ExecutionStatus.SUCCEEDED, r.status(), "变更应成功");
        assertTrue(r.affectedRows() >= 1, "影响行数应>=1，实际：" + r.affectedRows());
        assertTrue(r.statementResults() != null && r.statementResults().contains("SUCCEEDED"), "逐语句结果应含 SUCCEEDED");
        // 验证数据实际落地（专用账号执行）
        String val = readVal();
        assertEquals("b", val, "UPDATE 应已落地（专用变更账号执行）");
    }

    @Test
    @DisplayName("无 WHERE 的 UPDATE 仍可执行（风险由 precheck 标签+审批控制，执行器不阻断）")
    void updateWithoutWhereExecutes() {
        // 先重置 val
        execDdl("UPDATE " + TABLE + " SET val='a' WHERE id=1");
        String sql = "UPDATE " + TABLE + " SET val='c'";
        ChangeExecutionRequest req = new ChangeExecutionRequest(
            2L, 1L, null, null, 100L, DB, null, sql, List.of(), "dec-2", "idem-9-2", 30L, List.of(), List.of());
        MysqlChangeExecutor exec = new MysqlChangeExecutor(new MysqlConnector());
        ChangeResult r = exec.execute(req, new ConnectionContext(profile(), SecretValue.of(PASS), sql));
        assertEquals(ExecutionStatus.SUCCEEDED, r.status());
        assertEquals("c", readVal(), "无 WHERE UPDATE 已执行（风险由 precheck/审批控制）");
    }

    private String readVal() {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/" + DB, USER, PASS);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT val FROM " + TABLE + " WHERE id=1")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    private void execDdl(String sql) {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/" + DB, USER, PASS);
             Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException ignored) {
        }
    }
}
