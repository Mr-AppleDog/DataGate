package org.dromara.db.executor.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.EncryptedObject;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ExportExecutionRequest;
import org.dromara.db.core.domain.ExportResult;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.ExportExecutionGateway;
import org.dromara.db.executor.support.CsvExportRowCallback;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 导出执行网关实现（docs/02 §6.6、docs/06 §12）。
 *
 * <p>编排：解析数据源/凭据 → 纵深防御再解析锁定 SQL（只读单语句）→ 组装带脱敏上下文的 ExecutionPlan
 * → 连接器流式执行（执行器在 buildRow 应用服务端流式脱敏，plan 驱动）→ CsvExportRowCallback 写 CSV（公式注入防护）
 * → 加密对象存储（信封加密）→ 返回 ExportResult。临时文件流式避免大对象全量入内存。</p>
 *
 * <p>失败关闭：数据源未启用/凭据缺失/解析失败/非只读/多语句/存储不可用 → FAILED + 审计，不入脏对象。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
public class ExportExecutionGatewayImpl implements ExportExecutionGateway {

    private final IDbDataSourceService dataSourceService;
    private final ICredentialVaultService credentialVaultService;
    private final ConnectorRegistry connectorRegistry;
    private final Optional<EncryptedObjectStore> objectStore;
    private final IAuditService auditService;
    private final org.dromara.db.executor.support.DatagateMetrics metrics;

    public ExportExecutionGatewayImpl(IDbDataSourceService dataSourceService,
                                      ICredentialVaultService credentialVaultService,
                                      ConnectorRegistry connectorRegistry,
                                      Optional<EncryptedObjectStore> objectStore,
                                      IAuditService auditService,
                                      org.dromara.db.executor.support.DatagateMetrics metrics) {
        this.dataSourceService = dataSourceService;
        this.credentialVaultService = credentialVaultService;
        this.connectorRegistry = connectorRegistry;
        this.objectStore = objectStore;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    @Override
    public ExportResult execute(ExportExecutionRequest req) {
        long start = System.nanoTime();
        String executionNo = "export-" + UUID.randomUUID();
        io.micrometer.core.instrument.Timer.Sample timer = metrics.start();
        metrics.exportStarted();

        if (req == null || req.statement() == null || req.statement().isBlank()) {
            return ExportResult.failed(executionNo, DbErrorCode.QUERY_PARSE_FAILED.name(), durationMs(start));
        }

        DbDataSource ds = dataSourceService.queryById(req.dataSourceId());
        if (ds == null) {
            return ExportResult.failed(executionNo, DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE.name(), durationMs(start));
        }
        if (!DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            return ExportResult.failed(executionNo, DbErrorCode.RESOURCE_STATE_CONFLICT.name(), durationMs(start));
        }
        DataSourceType type;
        try {
            type = DataSourceType.valueOf(ds.getType());
        } catch (IllegalArgumentException e) {
            return ExportResult.failed(executionNo, DbErrorCode.RESOURCE_STATE_CONFLICT.name(), durationMs(start));
        }
        DataSourceConnector connector = connectorRegistry.get(type).orElse(null);
        if (connector == null) {
            return ExportResult.failed(executionNo, DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED.name(), durationMs(start));
        }

        // 纵深防御：再解析锁定 SQL（只读单语句，docs/06 §12 "不能用隐藏参数替换 SQL"）
        java.util.List<ParsedStatement> parsed;
        try {
            parsed = connector.queryParser().parse(req.statement());
        } catch (RuntimeException e) {
            return ExportResult.failed(executionNo, DbErrorCode.QUERY_PARSE_FAILED.name(), durationMs(start));
        }
        if (parsed.size() != 1) {
            return ExportResult.failed(executionNo, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), durationMs(start));
        }
        ParsedStatement stmt = parsed.get(0);
        if (!stmt.readonly() || stmt.requiredAction() == null
            || stmt.requiredAction().name().startsWith("CHANGE") || stmt.requiredAction().name().startsWith("REDIS")) {
            return ExportResult.failed(executionNo, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), durationMs(start));
        }

        String defaultDatabase = (req.databaseName() != null && !req.databaseName().isBlank())
            ? req.databaseName() : ds.getDefaultDatabase();

        ExecutionPlan plan = new ExecutionPlan(
            executionNo, req.userId(), req.dataSourceId(), defaultDatabase, req.schemaName(),
            sha256(req.statement()), stmt.normalizedStatement(), stmt.statementType(),
            req.resourceIds(), req.decisionId(), req.maxRows(), req.maxBytes(), req.maxExecutionSeconds(),
            Instant.now(), Instant.now().plusSeconds(Math.max(req.maxExecutionSeconds(), 1L)),
            req.maskingLevel(), req.columnPolicies(), req.columnUnmaskLevels());

        DbCredential cred = credentialVaultService.findActive(req.dataSourceId(), CredentialPurpose.QUERY)
            .orElse(null);
        if (cred == null) {
            return ExportResult.failed(executionNo, DbErrorCode.CREDENTIAL_INVALID.name(), durationMs(start));
        }

        if (objectStore.isEmpty()) {
            return ExportResult.failed(executionNo, DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(), durationMs(start));
        }
        EncryptedObjectStore store = objectStore.get();

        Path temp = null;
        try (SecretValue secret = credentialVaultService.resolveActiveSecret(cred.getId())) {
            ConnectionProfile profile = new ConnectionProfile(
                ds.getHost(), ds.getPort(), defaultDatabase, cred.getUsername(),
                parseOptions(ds.getConnectionOptions()), TlsMode.valueOf(ds.getTlsMode()), null, null);
            ConnectionContext ctx = new ConnectionContext(profile, secret, req.statement());

            temp = Files.createTempFile("datagate-export-", ".csv");
            ExecutionResultMeta execMeta;
            long csvBytes;
            long rowCount;
            try (OutputStream out = Files.newOutputStream(temp)) {
                CsvExportRowCallback cb = new CsvExportRowCallback(out, req.maxBytes());
                execMeta = connector.queryExecutor().execute(plan, ctx, cb);
                out.flush();
                csvBytes = cb.bytes();
                rowCount = cb.rowCount();
            }
            if (execMeta == null || execMeta.status() != ExecutionStatus.SUCCEEDED) {
                String ec = execMeta == null ? DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name() : execMeta.errorCode();
                auditExport(req, ds, AuditResult.FAILURE, "EXPORT_EXECUTE_FAILED", ec, rowCount, csvBytes);
                return ExportResult.failed(executionNo, ec, durationMs(start));
            }
            try (InputStream tempIn = Files.newInputStream(temp)) {
                EncryptedObject obj = store.create(tempIn, csvBytes);
                auditExport(req, ds, AuditResult.SUCCESS, "EXPORT_EXECUTE", null, rowCount, csvBytes);
                ExportResult ok = new ExportResult(executionNo, ExecutionStatus.SUCCEEDED, rowCount, csvBytes,
                    obj.objectKey(), obj.fileHash(), obj.encryptionKeyRef(), null, durationMs(start));
                metrics.stop(timer, "datagate.export.duration", "status", "SUCCEEDED");
                metrics.exportEnded();
                return ok;
            }
        } catch (Exception e) {
            log.warn("导出执行异常 jobId={}", req.jobId(), e);
            auditExport(req, ds, AuditResult.FAILURE, "EXPORT_EXECUTE_FAILED",
                DbErrorCode.INTERNAL_ERROR.name(), 0, 0);
            metrics.stop(timer, "datagate.export.duration", "status", "FAILED", "errorCode", DbErrorCode.INTERNAL_ERROR.name());
            metrics.increment("datagate.export.failed", "errorCode", DbErrorCode.INTERNAL_ERROR.name());
            metrics.exportEnded();
            return ExportResult.failed(executionNo, DbErrorCode.INTERNAL_ERROR.name(), durationMs(start));
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private void auditExport(ExportExecutionRequest req, DbDataSource ds, AuditResult result,
                             String action, String errorCode, long rowCount, long bytes) {
        if (ds == null) {
            return;
        }
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.EXPORT, action, req.userId(),
                Map.of(), "DATA_SOURCE", String.valueOf(req.dataSourceId()),
                Map.of("name", ds.getName(), "type", ds.getType()),
                result, req.sourceIp(), null, null,
                Map.of("executionNo", "export",
                    "jobId", String.valueOf(req.jobId()),
                    "rowCount", rowCount, "bytes", bytes,
                    "errorCode", errorCode == null ? "" : errorCode)));
        } catch (Exception e) {
            log.warn("导出审计写入失败 jobId={}", req.jobId(), e);
        }
    }

    private static long durationMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-err";
        }
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
