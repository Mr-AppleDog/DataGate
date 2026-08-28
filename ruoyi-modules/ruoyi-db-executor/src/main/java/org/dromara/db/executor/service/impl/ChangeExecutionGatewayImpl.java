package org.dromara.db.executor.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.ChangeExecutionGateway;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 变更执行网关实现（docs/02 §6.6、docs/06 §13，M5-02）。
 *
 * <p>编排：解析数据源 → 校验启用 → 连接器变更执行器可用 → 解密专用变更凭据（CHANGE）
 * → 组装 ConnectionContext → 调连接器逐语句执行 → 审计。失败关闭：未启用/凭据缺失/执行器缺失→FAILED。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
public class ChangeExecutionGatewayImpl implements ChangeExecutionGateway {

    private final IDbDataSourceService dataSourceService;
    private final ICredentialVaultService credentialVaultService;
    private final ConnectorRegistry connectorRegistry;
    private final IAuditService auditService;

    public ChangeExecutionGatewayImpl(IDbDataSourceService dataSourceService,
                                       ICredentialVaultService credentialVaultService,
                                       ConnectorRegistry connectorRegistry,
                                       IAuditService auditService) {
        this.dataSourceService = dataSourceService;
        this.credentialVaultService = credentialVaultService;
        this.connectorRegistry = connectorRegistry;
        this.auditService = auditService;
    }

    @Override
    public ChangeResult execute(ChangeExecutionRequest req) {
        long start = System.nanoTime();
        String executionNo = "change-" + java.util.UUID.randomUUID();
        if (req == null || req.statement() == null || req.statement().isBlank()) {
            return ChangeResult.failed(executionNo, DbErrorCode.QUERY_PARSE_FAILED.name(), ms(start));
        }
        DbDataSource ds = dataSourceService.queryById(req.dataSourceId());
        if (ds == null || !DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            return ChangeResult.failed(executionNo, DbErrorCode.RESOURCE_STATE_CONFLICT.name(), ms(start));
        }
        DataSourceType type;
        try {
            type = DataSourceType.valueOf(ds.getType());
        } catch (IllegalArgumentException e) {
            return ChangeResult.failed(executionNo, DbErrorCode.RESOURCE_STATE_CONFLICT.name(), ms(start));
        }
        DataSourceConnector connector = connectorRegistry.get(type).orElse(null);
        if (connector == null || connector.changeExecutor().isEmpty()) {
            return ChangeResult.failed(executionNo, DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED.name(), ms(start));
        }
        DbCredential cred = credentialVaultService.findActive(req.dataSourceId(), CredentialPurpose.CHANGE).orElse(null);
        if (cred == null) {
            return ChangeResult.failed(executionNo, DbErrorCode.CREDENTIAL_INVALID.name(), ms(start));
        }
        ChangeResult result;
        try (SecretValue secret = credentialVaultService.resolveActiveSecret(cred.getId())) {
            String defaultDb = (req.databaseName() != null && !req.databaseName().isBlank())
                ? req.databaseName() : ds.getDefaultDatabase();
            ConnectionProfile profile = new ConnectionProfile(
                ds.getHost(), ds.getPort(), defaultDb, cred.getUsername(),
                parseOptions(ds.getConnectionOptions()), TlsMode.valueOf(ds.getTlsMode()), null, null);
            ConnectionContext ctx = new ConnectionContext(profile, secret, req.statement());
            result = connector.changeExecutor().get().execute(req, ctx);
        } catch (RuntimeException e) {
            log.warn("变更执行异常 jobId={}", req.jobId(), e);
            result = ChangeResult.failed(executionNo, DbErrorCode.INTERNAL_ERROR.name(), ms(start));
        }
        audit(req, ds, result, start);
        return result;
    }

    private void audit(ChangeExecutionRequest req, DbDataSource ds, ChangeResult result, long start) {
        if (ds == null || result == null) {
            return;
        }
        AuditResult ar = result.status() == ExecutionStatus.SUCCEEDED ? AuditResult.SUCCESS : AuditResult.FAILURE;
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.CHANGE, "CHANGE_EXECUTE", req.userId(), Map.of(),
                "DATA_SOURCE", String.valueOf(req.dataSourceId()),
                Map.of("name", ds.getName(), "type", ds.getType()),
                ar, req.sourceIp(), null, null,
                Map.of("executionNo", result.executionNo(), "jobId", String.valueOf(req.jobId()),
                    "affectedRows", result.affectedRows(), "status", result.status().name(),
                    "errorCode", result.errorCode() == null ? "" : result.errorCode())));
        } catch (Exception e) {
            log.warn("变更审计写入失败 jobId={}", req.jobId(), e);
        }
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            return m.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
