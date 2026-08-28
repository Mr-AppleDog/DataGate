package org.dromara.db.executor.service.impl;

import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.AuthorizationDecisionService;
import org.dromara.db.core.authz.DecisionLimits;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.RowCallback;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.executor.domain.QueryExecutionRequest;
import org.dromara.db.core.spi.ResourcePathResolver;
import org.dromara.db.executor.support.StubDataSourceConnector;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询执行网关单元测试（纯桩，无 DB 无 Spring 上下文）。
 * 覆盖 docs/06 §4 执行流水线的失败关闭路径 + 正常路径 + cancel 路由。
 */
@Tag("unit")
class QueryExecutionGatewayImplTest {

    private StubDataSourceService dataSourceService;
    private StubCredentialVault credentialVault;
    private StubDecisionService decisionService;
    private StubAuditService auditService;
    private StubPathResolver pathResolver;
    private StubDataSourceConnector connector;
    private ConnectorRegistry registry;

    @BeforeEach
    void setUp() {
        dataSourceService = new StubDataSourceService();
        credentialVault = new StubCredentialVault();
        decisionService = new StubDecisionService();
        auditService = new StubAuditService();
        pathResolver = new StubPathResolver();
        connector = new StubDataSourceConnector();
        connector.cannedParsed = List.of(new ParsedStatement(
            "SELECT", List.of("/table/orders"), "select * from orders", "fp-orders",
            DbAction.QUERY, true));
        connector.cannedResult = new ExecutionResultMeta("exec-1", ExecutionStatus.SUCCEEDED,
            5, 1, 100, false, null);
        registry = new ConnectorRegistry(List.of(connector));
    }

    private QueryExecutionGatewayImpl newGateway(Optional<ResourcePathResolver> pr) {
        return new QueryExecutionGatewayImpl(dataSourceService, credentialVault, registry,
            decisionService, auditService, pr, java.util.Optional.empty());
    }

    private QueryExecutionRequest req() {
        return new QueryExecutionRequest(1L, "sess", "10.0.0.1", 1L, null, null,
            "select * from orders", null, null);
    }

    private RowCallback sink() {
        return new RowCallback() {
            int rows;
            @Override public void onHeader(RowHeader h) {}
            @Override public boolean onRow(List<RowCell> cells) { rows++; return true; }
            @Override public void onComplete() {}
            int rows() { return rows; }
        };
    }

    @Test
    void allowThenExecuteAuditsSuccess() {
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        assertEquals(ExecutionStatus.SUCCEEDED, r.status());
        assertEquals("exec-1", r.executionNo());
        assertTrue(connector.executeInvoked);
        assertFalse(auditService.appended.isEmpty());
        assertEquals("QUERY_EXECUTE", auditService.appended.get(0).action());
    }

    @Test
    void denyDecisionRejectedAndAuditedIsolated() {
        decisionService.deny = true;
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        assertEquals(ExecutionStatus.REJECTED, r.status());
        assertEquals(DbErrorCode.AUTH_RESOURCE_DENIED.name(), r.errorCode());
        assertFalse(connector.executeInvoked);
        assertFalse(auditService.isolated.isEmpty());
        assertEquals("QUERY_DENY", auditService.isolated.get(0).action());
    }

    @Test
    void dataSourceNotActiveRejected() {
        dataSourceService.ds.setStatus(DataSourceStatus.DISABLED.name());
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        assertEquals(ExecutionStatus.REJECTED, r.status());
        assertEquals(DbErrorCode.RESOURCE_STATE_CONFLICT.name(), r.errorCode());
        assertFalse(connector.executeInvoked);
    }

    @Test
    void multiStatementRejected() {
        connector.cannedParsed = List.of(
            new ParsedStatement("SELECT", List.of("/table/a"), "s1", "f1", DbAction.QUERY, true),
            new ParsedStatement("SELECT", List.of("/table/b"), "s2", "f2", DbAction.QUERY, true));
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        assertEquals(ExecutionStatus.REJECTED, r.status());
        assertEquals(DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), r.errorCode());
    }

    @Test
    void nonReadonlyRejected() {
        connector.cannedParsed = List.of(new ParsedStatement(
            "INSERT", List.of(), "insert into t values(1)", "f", DbAction.CHANGE_DML, false));
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        assertEquals(ExecutionStatus.REJECTED, r.status());
        assertEquals(DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), r.errorCode());
    }

    @Test
    void parseFailClosedRejected() {
        connector.parseThrows = true;
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        assertEquals(ExecutionStatus.REJECTED, r.status());
        assertEquals(DbErrorCode.QUERY_PARSE_FAILED.name(), r.errorCode());
    }

    @Test
    void noQueryCredentialThrowsAndAudits() {
        credentialVault.findActiveEmpty = true;
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        DbServiceException ex = assertThrows(DbServiceException.class, () -> gw.execute(req(), sink()));
        assertEquals(DbErrorCode.CREDENTIAL_INVALID, ex.getErrorCode());
        assertFalse(connector.executeInvoked);
    }

    @Test
    void pathResolverAbsentFailsClosed() {
        QueryExecutionGatewayImpl gw = newGateway(Optional.empty());
        DbServiceException ex = assertThrows(DbServiceException.class, () -> gw.execute(req(), sink()));
        assertEquals(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE, ex.getErrorCode());
        assertFalse(connector.executeInvoked);
    }

    @Test
    void cancelRoutesToExecutor() {
        QueryExecutionGatewayImpl gw = newGateway(Optional.of(pathResolver));
        ExecutionResultMeta r = gw.execute(req(), sink());
        gw.cancel(r.executionNo());
        assertEquals(r.executionNo(), connector.canceledExecutionNo);
    }

    // ============================ 桩 ============================

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

    static class StubDecisionService implements AuthorizationDecisionService {
        boolean deny;
        @Override public AccessDecision decide(DecisionRequest request) {
            if (deny) {
                return new AccessDecision("dec-deny", false, "DEFAULT_DENY", List.of(), null, 0L);
            }
            return new AccessDecision("dec-1", true, "ALLOW_BY_APPROVAL_GRANT", List.of("g1"),
                new DecisionLimits(500, 10 * 1024 * 1024, 30, MaskingLevel.MASKED), 1L);
        }
    }

    static class StubAuditService implements IAuditService {
        final List<AuditEventInput> appended = new ArrayList<>();
        final List<AuditEventInput> isolated = new ArrayList<>();
        @Override public String append(AuditEventInput input) { appended.add(input); return "e" + appended.size(); }
        @Override public String appendIsolated(AuditEventInput input) { isolated.add(input); return "i" + isolated.size(); }
        @Override public AuditChainVerification verifyChain(String chainKey) { return new AuditChainVerification(chainKey, 0, true, null); }
    }

    static class StubPathResolver implements ResourcePathResolver {
        @Override public List<Long> resolve(Long dataSourceId, String defaultDatabase, List<String> canonicalPaths) {
            return canonicalPaths.stream().map(p -> 1L).toList();
        }
    }
}
