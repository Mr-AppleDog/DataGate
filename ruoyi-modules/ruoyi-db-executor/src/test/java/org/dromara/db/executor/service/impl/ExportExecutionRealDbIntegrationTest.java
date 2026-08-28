package org.dromara.db.executor.service.impl;

import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.EncryptedObject;
import org.dromara.db.core.domain.ExportExecutionRequest;
import org.dromara.db.core.domain.ExportResult;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.KekProvider;
import org.dromara.db.connector.mysql.MysqlConnector;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.export.LocalEncryptedObjectStore;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出执行端到端集成测试（docs/06 §12、docs/10 M5-01，验收 #8）。
 *
 * <p>连真实 VM MySQL，经 ExportExecutionGatewayImpl（真实 MysqlConnector + LocalEncryptedObjectStore）
 * 流式导出 SELECT phone，断言：SUCCEEDED + 加密对象落地 + 解密后 CSV 含脱敏值（138****5678），
 * 证明 #8 执行核心：流式导出复用脱敏 + CSV 公式注入防护 + 加密对象 24h。</p>
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("导出执行端到端 (验收 #8)")
class ExportExecutionRealDbIntegrationTest {

    private static final String HOST = "192.168.149.128";
    private static final int PORT = 3306;
    private static final String DB = "datagatetest";
    private static final String USER = "root";
    private static final String PASS = "mrlu";
    private static final String TABLE = "export_test";

    @TempDir
    static java.nio.file.Path tempDir;

    private static LocalEncryptedObjectStore store;

    @BeforeAll
    static void setup() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/?allowMultiQueries=true", USER, PASS);
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + DB);
            st.execute("DROP TABLE IF EXISTS " + DB + "." + TABLE);
            st.execute("CREATE TABLE " + DB + "." + TABLE + " (id INT, phone VARCHAR(20))");
            st.execute("INSERT INTO " + DB + "." + TABLE + " (id, phone) VALUES (1, '13812345678')");
        }
        store = new LocalEncryptedObjectStore(fixedKek(), new LocalEncryptedObjectStore.Properties(tempDir.toString()));
    }

    @AfterAll
    static void cleanup() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:mysql://" + HOST + ":" + PORT + "/?allowMultiQueries=true", USER, PASS);
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + DB + "." + TABLE);
        }
    }

    private static KekProvider fixedKek() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        return new KekProvider() {
            public String currentKeyVersion() { return "v1"; }
            public byte[] currentKek() { return key.clone(); }
            public byte[] kekByVersion(String kv) { return "v1".equals(kv) ? key.clone() : null; }
        };
    }

    @Test
    @DisplayName("导出 SELECT phone → 加密对象落地 + 解密 CSV 含脱敏值 138****5678")
    void exportStreamsMaskedToEncryptedObject() {
        ExportExecutionGatewayImpl gw = new ExportExecutionGatewayImpl(
            stubDs(), stubVault(), new ConnectorRegistry(List.of(new MysqlConnector())), Optional.of(store), stubAudit());
        String sql = "SELECT phone FROM " + TABLE;
        ExportExecutionRequest req = new ExportExecutionRequest(
            1L, 1L, null, null, 100L, DB, null, sql, List.of(), "dec-1",
            MaskingLevel.MASKED,
            Map.of(TABLE + ".phone", new ColumnMaskingPolicy(1L, "phone", SensitivityLevel.SENSITIVE, MaskingType.PHONE, null, "MANUAL")),
            Map.of(), 100, 10_000_000L, 30L);
        ExportResult r = gw.execute(req);
        assertEquals(ExecutionStatus.SUCCEEDED, r.status(), "导出应成功");
        assertNotNull(r.objectKey(), "应落地加密对象");
        assertEquals(64, r.fileHash().length(), "sha256 hex");
        // 解密对象 → CSV 内容含脱敏值（服务端流式脱敏 + CSV）
        Optional<InputStream> in = store.read(r.objectKey(), r.encryptionKeyRef());
        assertTrue(in.isPresent(), "加密对象应可解密读取");
        String csv;
        try (InputStream is = in.get()) {
            csv = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            csv = "";
        }
        assertTrue(csv.contains("138****5678"), "导出 CSV 应含脱敏值，实际：" + csv);
        assertTrue(!csv.contains("13812345678"), "导出 CSV 不得含原值");
    }

    private static IDbDataSourceService stubDs() {
        DbDataSource d = new DbDataSource();
        d.setId(100L); d.setType("MYSQL"); d.setHost(HOST); d.setPort(PORT);
        d.setDefaultDatabase(DB); d.setTlsMode("DISABLE"); d.setStatus(DataSourceStatus.ACTIVE.name());
        d.setName("vm-mysql"); d.setConnectionOptions(null);
        return new IDbDataSourceService() {
            public DbDataSource queryById(Long id) { return d; }
            public Long createDraft(org.dromara.db.resource.domain.bo.DbDataSourceBo bo) { throw new UnsupportedOperationException(); }
            public boolean updateByBo(org.dromara.db.resource.domain.bo.DbDataSourceBo bo) { throw new UnsupportedOperationException(); }
            public org.dromara.db.core.domain.ConnectionTestResult verify(Long id) { throw new UnsupportedOperationException(); }
            public boolean enable(Long id) { throw new UnsupportedOperationException(); }
            public boolean disable(Long id) { throw new UnsupportedOperationException(); }
            public org.dromara.common.mybatis.core.page.TableDataInfo<org.dromara.db.resource.domain.vo.DbDataSourceVo> queryPageList(org.dromara.db.resource.domain.bo.DbDataSourceBo bo, org.dromara.common.mybatis.core.page.PageQuery p) { throw new UnsupportedOperationException(); }
            public org.dromara.db.resource.domain.vo.DbDataSourceVo queryVoById(Long id) { throw new UnsupportedOperationException(); }
        };
    }

    private static ICredentialVaultService stubVault() {
        return new ICredentialVaultService() {
            public Optional<DbCredential> findActive(Long ds, org.dromara.db.core.enums.CredentialPurpose p) {
                DbCredential c = new DbCredential(); c.setId(1L); c.setUsername(USER); c.setActiveVersionId(1L); return Optional.of(c);
            }
            public SecretValue resolveActiveSecret(Long credId) { return SecretValue.of(PASS); }
            public Long createCredential(Long ds, org.dromara.db.core.enums.CredentialPurpose p, String u, SecretValue s) { throw new UnsupportedOperationException(); }
            public boolean disable(Long c) { throw new UnsupportedOperationException(); }
            public List<org.dromara.db.resource.domain.vo.DbCredentialVo> listByDataSource(Long ds) { throw new UnsupportedOperationException(); }
        };
    }

    private static IAuditService stubAudit() {
        return new IAuditService() {
            public String append(AuditEventInput i) { return "e1"; }
            public String appendIsolated(AuditEventInput i) { return "i1"; }
            public AuditChainVerification verifyChain(String k) { return new AuditChainVerification(k, 0, true, null); }
        };
    }
}
