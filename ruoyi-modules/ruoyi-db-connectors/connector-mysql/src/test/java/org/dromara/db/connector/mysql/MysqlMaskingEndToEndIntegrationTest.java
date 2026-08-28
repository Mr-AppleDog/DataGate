package org.dromara.db.connector.mysql;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 服务端流式脱敏端到端集成测试（docs/06 §11、docs/10 M5-05c，验收 #13）。
 *
 * <p>连真实 VM MySQL（192.168.149.128），建表插入手机号，经 MysqlQueryExecutor 执行
 * 携带 maskingLevel=MASKED + 列策略的 ExecutionPlan，断言结果单元格已脱敏（138****5678）；
 * 并验证别名 SELECT phone AS x 仍按基列名 lineage 命中策略被掩码（防借名绕过）。</p>
 *
 * <p>需 -DtestTags=integration,unit 触发（默认 unit 组不跑）。</p>
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("MySQL 服务端流式脱敏端到端 (验收 #13)")
class MysqlMaskingEndToEndIntegrationTest {

    private static final String HOST = "192.168.149.128";
    private static final int PORT = 3306;
    private static final String DB = "datagatetest";
    private static final String USER = "root";
    private static final String PASS = "mrlu";
    private static final String TABLE = "mask_test";

    private final MysqlQueryParser parser = new MysqlQueryParser();

    @BeforeAll
    static void setup() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/?allowMultiQueries=true", USER, PASS);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + DB);
            st.execute("DROP TABLE IF EXISTS " + DB + "." + TABLE);
            st.execute("CREATE TABLE " + DB + "." + TABLE + " (id INT, phone VARCHAR(20))");
            st.execute("INSERT INTO " + DB + "." + TABLE + " (id, phone) VALUES (1, '13812345678')");
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

    private ExecutionPlan maskedPlan(String sql) {
        return new ExecutionPlan(
            "p1", 1L, 100L, DB, null, "hash", sql, "SELECT", List.of(), "dec-1",
            100, 10_000_000, 30, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60),
            MaskingLevel.MASKED,
            Map.of(TABLE + ".phone", new ColumnMaskingPolicy(1L, "phone", SensitivityLevel.SENSITIVE, MaskingType.PHONE, null, "MANUAL")),
            Map.of());
    }

    /** 收集首行首列值 */
    private static final class FirstCellCallback implements RowCallback {
        String value;
        @Override public void onHeader(RowHeader h) {}
        @Override public boolean onRow(List<RowCell> cells) {
            if (value == null && cells != null && !cells.isEmpty()) {
                value = cells.get(0).value();
            }
            return false; // 取首行即止
        }
        @Override public void onComplete() {}
    }

    @Test
    @DisplayName("SELECT phone → 服务端掩码 138****5678（原值不达前端）")
    void phoneMaskedOnDirectSelect() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        FirstCellCallback cb = new FirstCellCallback();
        ExecutionResultMeta m = exec.execute(maskedPlan("SELECT phone FROM " + TABLE),
            new ConnectionContext(profile(), SecretValue.of(PASS), "SELECT phone FROM " + TABLE), cb);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status(), "执行应成功");
        assertEquals("138****5678", cb.value, "敏感列应服务端掩码");
    }

    @Test
    @DisplayName("SELECT phone AS x → 别名不绕过，基列名 lineage 命中策略仍掩码（防借名绕过）")
    void aliasDoesNotBypassMasking() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        FirstCellCallback cb = new FirstCellCallback();
        exec.execute(maskedPlan("SELECT phone AS x FROM " + TABLE),
            new ConnectionContext(profile(), SecretValue.of(PASS), "SELECT phone AS x FROM " + TABLE), cb);
        assertEquals("138****5678", cb.value, "别名 AS x 不得绕过脱敏（基列名 lineage）");
    }

    @Test
    @DisplayName("SELECT id（无敏感策略列）→ 原值透传，不误掩码")
    void nonSensitiveColumnPassthrough() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        // resolver 在真实流程会返回所有列（含未标注默认 PUBLIC/NONE）；此处给 id 列默认非敏感策略
        ExecutionPlan plan = new ExecutionPlan(
            "p2", 1L, 100L, DB, null, "h", "SELECT id FROM " + TABLE, "SELECT", List.of(), "dec-2",
            100, 10_000_000, 30, Instant.now().minusSeconds(60), Instant.now().plusSeconds(60),
            MaskingLevel.MASKED,
            Map.of(TABLE + ".id", new ColumnMaskingPolicy(2L, "id", SensitivityLevel.PUBLIC, MaskingType.NONE, null, null)),
            Map.of());
        FirstCellCallback cb = new FirstCellCallback();
        exec.execute(plan, new ConnectionContext(profile(), SecretValue.of(PASS), "SELECT id FROM " + TABLE), cb);
        assertEquals("1", cb.value, "非敏感无策略列应透传");
    }

    @Test
    @DisplayName("SELECT CONCAT(phone,'') AS c → 表达式未知来源 prod→HIDDEN（value=null）")
    void unknownExpressionHidden() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        FirstCellCallback cb = new FirstCellCallback();
        exec.execute(maskedPlan("SELECT CONCAT(phone, '') AS c FROM " + TABLE),
            new ConnectionContext(profile(), SecretValue.of(PASS), "SELECT CONCAT(phone, '') AS c FROM " + TABLE), cb);
        assertTrue(cb.value == null, "未知来源表达式在 prod 应 HIDDEN（value=null），实际：" + cb.value);
    }
}
