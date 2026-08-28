package org.dromara.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 迁移集成测试（docs/10：迁移可重复执行）。
 * 对元库（VM PostgreSQL 18）执行全部迁移，验证 V11/V12 慢查询与告警表 SQL 语法正确 + 表可创建。
 * 仅 -DtestTags=integration 触发。
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("Flyway 迁移可重复执行 + M4 表存在")
class FlywayMigrationIntegrationTest {

    private static final String URL = System.getProperty("datagate.meta.url", "jdbc:postgresql://192.168.149.128:5432/datagate");
    private static final String USER = System.getProperty("datagate.meta.user", "postgres");
    private static final String PASS = System.getProperty("datagate.meta.pass", "mrlu");

    @Test
    @DisplayName("V1-V12 全部迁移成功 + M4 慢查询/告警表存在")
    void migrationsApplyAndM4TablesExist() throws Exception {
        Flyway flyway = Flyway.configure()
            .dataSource(URL, USER, PASS)
            .locations("filesystem:src/main/resources/db/migration")
            .load();
        flyway.migrate();

        String[] m4Tables = {
            "dbg_slow_source", "dbg_slow_cursor", "dbg_slow_fingerprint",
            "dbg_slow_sample", "dbg_slow_bucket", "dbg_slow_governance_log",
            "dbg_alert_rule", "dbg_alert_event",
            "dbg_notification_channel", "dbg_notification_delivery"
        };
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            for (String t : m4Tables) {
                s.executeQuery("SELECT 1 FROM " + t + " LIMIT 0");
            }
        }
        // 默认规则（V12 9101-9105）存在性
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT count(*) FROM dbg_alert_rule WHERE id IN (9101,9102,9103,9104,9105)");
            assertTrue(rs.next() && rs.getInt(1) == 5, "默认告警规则 9101-9105 应存在");
        }
    }
}
