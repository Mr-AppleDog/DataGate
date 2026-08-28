package org.dromara.db.executor.service.impl;

import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.EncryptedObject;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ExportExecutionRequest;
import org.dromara.db.core.domain.ExportResult;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.dromara.db.executor.support.StubDataSourceConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出执行网关单元测试（纯桩，无 DB 无 Spring）。
 * 覆盖 docs/06 §12：流式导出→CSV→加密对象→结果元数据；失败关闭路径。
 *
 * @author DataGate
 */
@Tag("unit")
class ExportExecutionGatewayImplTest {

    private StubDataSourceService dataSourceService;
    private StubCredentialVault credentialVault;
    private StubDataSourceConnector connector;
    private ConnectorRegistry registry;
    private CapturingObjectStore store;
    private StubAuditService auditService;

    @BeforeEach
    void setUp() {
        dataSourceService = new StubDataSourceService();
        credentialVault = new StubCredentialVault();
        store = new CapturingObjectStore();
        auditService = new StubAuditService();
        connector = new StubDataSourceConnector();
        connector.cannedParsed = List.of(new ParsedStatement(
            "SELECT", List.of("/table/orders"), "select * from orders", "fp", DbAction.QUERY, true));
        connector.cannedResult = new ExecutionResultMeta("exec-1", ExecutionStatus.SUCCEEDED, 0, 0, 0, false, null);
        connector.emitRows = 3;
        registry = new ConnectorRegistry(List.of(connector));
    }

    private ExportExecutionGatewayImpl gateway(Optional<EncryptedObjectStore> os) {
        return new ExportExecutionGatewayImpl(dataSourceService, credentialVault, registry, os, auditService);
    }

    private ExportExecutionRequest req() {
        return new ExportExecutionRequest(50L, 1L, "sess", "10.0.0.1", 1L, null, null,
            "select * from orders", List.of(1L), "dec-1", MaskingLevel.UNMASKED,
            Map.of(), Map.of(), 100, 1_000_000L, 30L);
    }

    @Test
    void streams_rows_to_csv_and_encrypted_object() {
        ExportResult r = gateway(Optional.of(store)).execute(req());
        assertEquals(ExecutionStatus.SUCCEEDED, r.status());
        assertEquals(3, r.rowCount());
        assertEquals("obj-key", r.objectKey());
        assertNotNull(r.fileHash());
        assertEquals(64, r.fileHash().length()); // sha256 hex
        assertTrue(r.resultBytes() > 0);
        // 捕获的 CSV：列头 c + 3 行 v0/v1/v2，CRLF
        String csv = new String(store.captured, StandardCharsets.UTF_8);
        assertTrue(csv.contains("c\r\n"), csv);
        assertTrue(csv.contains("v0\r\n"), csv);
        assertTrue(csv.contains("v2\r\n"), csv);
        assertTrue(connector.executeInvoked);
    }

    @Test
    void store_unavailable_fails_closed() {
        ExportResult r = gateway(Optional.empty()).execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(), r.errorCode());
    }

    @Test
    void non_readonly_statement_fails_closed() {
        connector.cannedParsed = List.of(new ParsedStatement(
            "INSERT", List.of(), "insert into t values(1)", "f", DbAction.CHANGE_DML, false));
        ExportResult r = gateway(Optional.of(store)).execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), r.errorCode());
    }

    @Test
    void multi_statement_fails_closed() {
        connector.cannedParsed = List.of(
            new ParsedStatement("SELECT", List.of("/table/a"), "s1", "f1", DbAction.QUERY, true),
            new ParsedStatement("SELECT", List.of("/table/b"), "s2", "f2", DbAction.QUERY, true));
        ExportResult r = gateway(Optional.of(store)).execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), r.errorCode());
    }

    @Test
    void parse_fail_closed() {
        connector.parseThrows = true;
        ExportResult r = gateway(Optional.of(store)).execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.QUERY_PARSE_FAILED.name(), r.errorCode());
    }

    @Test
    void no_credential_fails_closed() {
        credentialVault.findActiveEmpty = true;
        ExportResult r = gateway(Optional.of(store)).execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.CREDENTIAL_INVALID.name(), r.errorCode());
    }

    // ============================ 桩 ============================

    static class CapturingObjectStore implements EncryptedObjectStore {
        byte[] captured;

        @Override
        public EncryptedObject create(InputStream in, long expectedSize) {
            try {
                captured = in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return new EncryptedObject("obj-key", sha256Hex(captured), "v1", captured.length);
        }

        @Override
        public Optional<InputStream> read(String objectKey, String encryptionKeyRef) {
            return Optional.of(new ByteArrayInputStream(captured));
        }

        @Override
        public void delete(String objectKey) {
        }

        private static String sha256Hex(byte[] data) {
            try {
                byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(data);
                StringBuilder sb = new StringBuilder(h.length * 2);
                for (byte b : h) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return "err";
            }
        }
    }

    static class StubDataSourceService implements IDbDataSourceService {
        DbDataSource ds = activeDs();

        static DbDataSource activeDs() {
            DbDataSource d = new DbDataSource();
            d.setId(1L);
            d.setType("MYSQL");
            d.setHost("h");
            d.setPort(3306);
            d.setDefaultDatabase("test");
            d.setTlsMode("PREFER");
            d.setStatus(DataSourceStatus.ACTIVE.name());
            d.setName("ds1");
            d.setConnectionOptions(null);
            return d;
        }

        @Override public DbDataSource queryById(Long id) { return ds; }
        @Override public Long createDraft(org.dromara.db.resource.domain.bo.DbDataSourceBo bo) { throw new UnsupportedOperationException(); }
        @Override public boolean updateByBo(org.dromara.db.resource.domain.bo.DbDataSourceBo bo) { throw new UnsupportedOperationException(); }
        @Override public org.dromara.db.core.domain.ConnectionTestResult verify(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean enable(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean disable(Long id) { throw new UnsupportedOperationException(); }
        @Override public org.dromara.common.mybatis.core.page.TableDataInfo<org.dromara.db.resource.domain.vo.DbDataSourceVo> queryPageList(org.dromara.db.resource.domain.bo.DbDataSourceBo bo, org.dromara.common.mybatis.core.page.PageQuery pageQuery) { throw new UnsupportedOperationException(); }
        @Override public org.dromara.db.resource.domain.vo.DbDataSourceVo queryVoById(Long id) { throw new UnsupportedOperationException(); }
    }

    static class StubCredentialVault implements ICredentialVaultService {
        boolean findActiveEmpty;
        @Override public Optional<DbCredential> findActive(Long dataSourceId, org.dromara.db.core.enums.CredentialPurpose purpose) {
            if (findActiveEmpty) return Optional.empty();
            DbCredential c = new DbCredential();
            c.setId(10L);
            c.setUsername("u");
            c.setActiveVersionId(100L);
            return Optional.of(c);
        }
        @Override public SecretValue resolveActiveSecret(Long credentialId) { return SecretValue.of("pw"); }
        @Override public Long createCredential(Long dataSourceId, org.dromara.db.core.enums.CredentialPurpose purpose, String username, SecretValue plaintext) { throw new UnsupportedOperationException(); }
        @Override public boolean disable(Long credentialId) { throw new UnsupportedOperationException(); }
        @Override public List<org.dromara.db.resource.domain.vo.DbCredentialVo> listByDataSource(Long dataSourceId) { throw new UnsupportedOperationException(); }
    }

    static class StubAuditService implements IAuditService {
        @Override public String append(AuditEventInput input) { return "e1"; }
        @Override public String appendIsolated(AuditEventInput input) { return "i1"; }
        @Override public AuditChainVerification verifyChain(String chainKey) { return new AuditChainVerification(chainKey, 0, true, null); }
    }
}
