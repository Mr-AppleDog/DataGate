package org.dromara.db.connector.postgresql;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.SlowQueryProvider.SlowQueryPage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL 慢查询采集器真实引擎集成测试（docs/07 §4.3）。
 * 连接 VM PostgreSQL 18 pg_stat_statements。仅 -DtestTags=integration 触发。
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("PostgreSQL 慢查询采集器集成测试 pg_stat_statements")
class PostgresqlSlowQueryProviderIntegrationTest {

    private static final String HOST = System.getProperty("datagate.pg.host", "192.168.149.128");
    private static final int PORT = Integer.getInteger("datagate.pg.port", 5432);
    private static final String USER = System.getProperty("datagate.pg.user", "postgres");
    private static final String PASS = System.getProperty("datagate.pg.pass", "mrlu");
    private static final String DB = System.getProperty("datagate.pg.db", "postgres");

    @BeforeAll
    static void ensureExtension() throws Exception {
        try (Connection c = rawConn(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            c.commit();
        }
        // 产生执行以填充摘要（若已 preload）
        try (Connection c = rawConn(); Statement s = c.createStatement()) {
            s.executeQuery("SELECT 1");
        }
    }

    private static Connection rawConn() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", USER);
        p.setProperty("password", PASS);
        Connection c = DriverManager.getConnection(
            "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB + "?sslmode=disable&connectTimeout=5&loginTimeout=5", p);
        c.setAutoCommit(false);
        return c;
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile(HOST, PORT, DB, USER, Map.of(), TlsMode.DISABLE,
            Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("扩展已安装：已 preload 返回摘要；未 preload 明确能力不可用（docs/07 §4.3）")
    void pullWithExtensionInstalled() {
        PostgresqlSlowQueryProvider provider = new PostgresqlSlowQueryProvider(new PostgresqlConnector());
        try {
            SlowQueryPage page = provider.pull(profile(), SecretValue.of(PASS), null, 100);
            if (!page.records().isEmpty()) {
                SlowQueryRecord r = page.records().get(0);
                assertEquals("POSTGRESQL", r.engineType());
                assertEquals("AGGREGATED", r.ingestQuality());
                assertFalse(r.fingerprint().isBlank());
                assertNotNull(r.nativeFingerprint());
            }
            assertNotNull(page.nextCursor());
        } catch (DbServiceException e) {
            assertEquals(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, e.getErrorCode(),
                "未 preload 应明确能力不可用，不视零数据为健康（docs/07 §4.3）");
        }
    }

    @Test
    @DisplayName("扩展未安装：抛 RESOURCE_CAPABILITY_UNSUPPORTED（不视零数据为健康，docs/07 §4.3）")
    void extensionMissingThrowsCapabilityUnsupported() throws Exception {
        try (Connection c = rawConn(); Statement s = c.createStatement()) {
            s.execute("DROP EXTENSION IF EXISTS pg_stat_statements");
            c.commit();
        }
        PostgresqlSlowQueryProvider provider = new PostgresqlSlowQueryProvider(new PostgresqlConnector());
        DbServiceException ex = assertThrows(DbServiceException.class,
            () -> provider.pull(profile(), SecretValue.of(PASS), null, 100));
        assertEquals(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, ex.getErrorCode());
        // 恢复扩展，避免影响其他测试
        try (Connection c = rawConn(); Statement s = c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            c.commit();
        }
    }
}
