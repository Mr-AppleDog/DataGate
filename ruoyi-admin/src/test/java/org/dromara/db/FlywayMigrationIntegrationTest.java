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
 * 对元库（VM PostgreSQL 18）执行全部迁移，验证 M4 表与 V20 数据源管理菜单。
 * 仅 -DtestTags=integration 触发。
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("Flyway 迁移可重复执行 + M4 表/V20 数据源管理菜单存在")
class FlywayMigrationIntegrationTest {

    private static final String URL = System.getProperty("datagate.meta.url", "jdbc:postgresql://192.168.149.128:5432/datagate");
    private static final String USER = System.getProperty("datagate.meta.user", "postgres");
    private static final String PASS = System.getProperty("datagate.meta.pass", "mrlu");

    @Test
    @DisplayName("V1-V20 全部迁移成功 + M4 表与业务菜单存在")
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

        // V19：一级导航按 PRD 业务域收敛，查询控制台只由动态菜单提供。
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            var roots = s.executeQuery("""
                SELECT count(*) FROM sys_menu
                WHERE menu_id IN (9200, 9290, 9291, 9292)
                  AND parent_id = 0 AND visible = '0' AND status = '0'
                """);
            assertTrue(roots.next() && roots.getInt(1) == 4, "V19 四个 DataGate 一级业务栏目应可见");

            var console = s.executeQuery("""
                SELECT count(*) FROM sys_menu
                WHERE menu_id = 9293 AND parent_id = 9200
                  AND component = 'db/console/index' AND perms = 'db:console:query'
                """);
            assertTrue(console.next() && console.getInt(1) == 1, "查询控制台应归属数据工作台");

            var duplicates = s.executeQuery("""
                SELECT count(*) FROM sys_menu
                WHERE parent_id = 0 AND visible = '0' AND status = '0'
                  AND menu_name IN ('数据库治理', '数据治理')
                """);
            assertTrue(duplicates.next() && duplicates.getInt(1) == 0, "不应保留重复的数据库治理入口");

            // V20（RES-001~005/CRED-001~004/007）：数据源管理页面与服务端功能权限一致。
            var datasourcePage = s.executeQuery("""
                SELECT count(*) FROM sys_menu
                WHERE menu_id = 20100 AND parent_id = 9292
                  AND component = 'db/datasource/index'
                  AND perms = 'db:datasource:manage'
                  AND visible = '0' AND status = '0'
                """);
            assertTrue(datasourcePage.next() && datasourcePage.getInt(1) == 1,
                "V20 数据源管理页面应归属数据资产");

            var datasourcePermissions = s.executeQuery("""
                SELECT count(*) FROM sys_menu
                WHERE parent_id = 20100 AND menu_type = 'F'
                  AND perms IN (
                    'db:datasource:list', 'db:datasource:query', 'db:datasource:add',
                    'db:datasource:edit', 'db:datasource:verify', 'db:datasource:enable',
                    'db:datasource:disable', 'db:datasource:sync', 'db:credential:list',
                    'db:credential:add', 'db:credential:disable'
                  )
                """);
            assertTrue(datasourcePermissions.next() && datasourcePermissions.getInt(1) == 11,
                "V20 数据源与凭据管理的 11 个服务端权限应完整挂载");
        }
    }
}
