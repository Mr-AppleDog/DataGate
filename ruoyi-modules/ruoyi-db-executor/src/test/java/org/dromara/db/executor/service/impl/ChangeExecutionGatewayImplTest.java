package org.dromara.db.executor.service.impl;

import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.ChangeExecutor;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.dromara.db.executor.support.StubDataSourceConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 变更执行网关单元测试（纯桩，无 DB 无 Spring）。
 *
 * @author DataGate
 */
@Tag("unit")
class ChangeExecutionGatewayImplTest {

    private StubDataSourceService dataSourceService;
    private StubCredentialVault credentialVault;
    private StubDataSourceConnector connector;
    private ConnectorRegistry registry;
    private StubAuditService auditService;
    private StubChangeExecutor changeExecutor;

    @BeforeEach
    void setUp() {
        dataSourceService = new StubDataSourceService();
        credentialVault = new StubCredentialVault();
        auditService = new StubAuditService();
        connector = new StubDataSourceConnector();
        changeExecutor = new StubChangeExecutor();
        changeExecutor.canned = new ChangeResult("exec-1", ExecutionStatus.SUCCEEDED, 5, "[{idx:0,status:SUCCEEDED,affectedRows:5}]", null, 10);
        connector.cannedChangeExecutor = changeExecutor;
        registry = new ConnectorRegistry(List.of(connector), java.util.Optional.empty());
    }

    private ChangeExecutionGatewayImpl gateway() {
        return new ChangeExecutionGatewayImpl(dataSourceService, credentialVault, registry, auditService, new org.dromara.db.executor.support.DatagateMetrics(java.util.Optional.empty()));
    }

    private ChangeExecutionRequest req() {
        return new ChangeExecutionRequest(60L, 1L, "sess", "10.0.0.1", 1L, null, null,
            "UPDATE t SET x=1 WHERE id=1", List.of(1L), "dec-1", "idem-1", 60L, java.util.List.of(), java.util.List.of());
    }

    @Test
    void execute_via_change_executor() {
        ChangeResult r = gateway().execute(req());
        assertEquals(ExecutionStatus.SUCCEEDED, r.status());
        assertEquals(5, r.affectedRows());
        assertEquals("exec-1", r.executionNo());
    }

    @Test
    void change_executor_unsupported_fails_closed() {
        connector.cannedChangeExecutor = null;
        ChangeResult r = gateway().execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED.name(), r.errorCode());
    }

    @Test
    void no_change_credential_fails_closed() {
        credentialVault.findActiveEmpty = true;
        ChangeResult r = gateway().execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.CREDENTIAL_INVALID.name(), r.errorCode());
    }

    @Test
    void datasource_disabled_fails_closed() {
        dataSourceService.ds.setStatus(DataSourceStatus.DISABLED.name());
        ChangeResult r = gateway().execute(req());
        assertEquals(ExecutionStatus.FAILED, r.status());
        assertEquals(DbErrorCode.RESOURCE_STATE_CONFLICT.name(), r.errorCode());
    }

    // ============================ 桩 ============================

    static class StubChangeExecutor implements ChangeExecutor {
        ChangeResult canned;
        @Override
        public ChangeResult execute(ChangeExecutionRequest req, ConnectionContext ctx) {
            ctx.secret().destroy();
            return canned;
        }
    }

    static class StubDataSourceService implements IDbDataSourceService {
        DbDataSource ds = activeDs();
        static DbDataSource activeDs() {
            DbDataSource d = new DbDataSource();
            d.setId(1L); d.setType("MYSQL"); d.setHost("h"); d.setPort(3306);
            d.setDefaultDatabase("test"); d.setTlsMode("PREFER");
            d.setStatus(DataSourceStatus.ACTIVE.name()); d.setName("ds1"); d.setConnectionOptions(null);
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
            c.setId(20L); c.setUsername("change_user"); c.setActiveVersionId(200L);
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
