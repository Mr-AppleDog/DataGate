package org.dromara.db.connector.mysql;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.enums.TlsMode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 慢查询采集器真实引擎集成测试（docs/07 §4.2）。
 * 连接 VM MySQL 8.4 performance_schema 摘要差值。仅 -DtestTags=integration 触发。
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("MySQL 慢查询采集器集成测试 performance_schema")
class MysqlSlowQueryProviderIntegrationTest {

    private static final String HOST = System.getProperty("datagate.mysql.host", "192.168.149.128");
    private static final int PORT = Integer.getInteger("datagate.mysql.port", 3306);
    private static final String USER = System.getProperty("datagate.mysql.user", "root");
    private static final String PASS = System.getProperty("datagate.mysql.pass", "mrlu");
    private static final String DB = System.getProperty("datagate.mysql.db", "mysql");

    @BeforeAll
    static void warmPerfSchema() throws Exception {
        try (Connection c = rawConn(); Statement s = c.createStatement()) {
            s.executeQuery("SELECT 1");
        }
    }

    private static Connection rawConn() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", USER);
        p.setProperty("password", PASS);
        return DriverManager.getConnection(
            "jdbc:mysql://" + HOST + ":" + PORT + "/" + DB + "?useSSL=false&connectTimeout=5000&socketTimeout=10000", p);
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile(HOST, PORT, DB, USER, Map.of(), TlsMode.DISABLE,
            Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("首次轮询返回 performance_schema 摘要 AGGREGATED")
    void firstPullReturnsDigests() {
        MysqlSlowQueryProvider provider = new MysqlSlowQueryProvider(new MysqlConnector());
        SlowQueryPage page = provider.pull(profile(), SecretValue.of(PASS), null, 100);
        assertFalse(page.records().isEmpty(), "首次轮询应返回至少一条摘要");
        SlowQueryRecord r = page.records().get(0);
        assertEquals("MYSQL", r.engineType());
        assertEquals("AGGREGATED", r.ingestQuality());
        assertFalse(r.fingerprint().isBlank(), "portableFingerprint 必须非空");
        assertFalse(r.normalizedStatement().isBlank(), "归一化语句必须非空");
        assertNotNull(r.nativeFingerprint(), "nativeFingerprint=DIGEST");
        assertTrue(r.durationMicros() > 0, "耗时必须为正");
        assertNotNull(page.nextCursor(), "必须返回下一游标");
    }

    @Test
    @DisplayName("游标往返：第二次轮询产出差值")
    void cursorRoundTripProducesDiff() throws Exception {
        MysqlSlowQueryProvider provider = new MysqlSlowQueryProvider(new MysqlConnector());
        SlowQueryPage page1 = provider.pull(profile(), SecretValue.of(PASS), null, 100);
        try (Connection c = rawConn(); Statement s = c.createStatement()) {
            s.executeQuery("SELECT 2");
        }
        SlowQueryPage page2 = provider.pull(profile(), SecretValue.of(PASS), page1.nextCursor(), 100);
        assertNotNull(page2);
        assertFalse(page2.records().isEmpty(), "差值轮询应产出新增执行");
    }
}
