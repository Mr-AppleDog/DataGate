package org.dromara.db.workflow.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.AuthorizationDecisionService;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.ExportExecutionRequest;
import org.dromara.db.core.domain.ExportResult;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.spi.ColumnMaskingPolicyResolver;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.ExportExecutionGateway;
import org.dromara.db.workflow.domain.DbExportJob;
import org.dromara.db.workflow.mapper.DbExportJobMapper;
import org.dromara.db.workflow.service.ExportApprovalCallbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 导出审批回调实现（docs/03 §10.2、docs/06 §12，M5-01c）。
 *
 * <p>两级审批通过 → 执行前重鉴权（EXPORT 再判定）→ 解密锁定 SQL → 重解析列脱敏上下文
 * → 组装 ExportExecutionRequest → ExportExecutionGateway 流式导出 → 落地结果（objectKey/fileHash/rowCount/status/expires 24h）。
 * 拒绝/撤销不执行。幂等：非 PENDING_APPROVED 状态不重复处理。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportApprovalCallbackServiceImpl implements ExportApprovalCallbackService {

    private static final long OBJECT_TTL_SECONDS = 24 * 3600L;

    private final DbExportJobMapper exportJobMapper;
    private final AuthorizationDecisionService decisionService;
    private final Optional<ColumnMaskingPolicyResolver> columnPolicyResolver;
    private final Optional<EncryptedObjectStore> objectStore;
    private final Optional<ExportExecutionGateway> exportExecutionGateway;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onApproval(Long jobId, Long finalApproverId) {
        DbExportJob job = exportJobMapper.selectById(jobId);
        if (job == null) {
            log.warn("导出回调：工单不存在 {}", jobId);
            return;
        }
        if (!"PENDING_APPROVAL".equals(job.getStatus()) && !"APPROVED".equals(job.getStatus())) {
            return; // 幂等
        }
        if (objectStore.isEmpty() || exportExecutionGateway.isEmpty()) {
            log.warn("导出执行依赖缺失（对象存储/执行网关未注入），jobId={}", jobId);
            markFailed(job, finalApproverId, DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(), "导出执行依赖未配置");
            return;
        }
        // 1. 解密锁定 SQL
        String sql;
        try (InputStream in = objectStore.get().read(job.getStatementEncrypted(), "v1").orElse(null)) {
            if (in == null) {
                markFailed(job, finalApproverId, DbErrorCode.QUERY_PARSE_FAILED.name(), "锁定 SQL 解密失败");
                return;
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            markFailed(job, finalApproverId, DbErrorCode.QUERY_PARSE_FAILED.name(), "锁定 SQL 解密异常");
            return;
        }
        // 2. 执行前重鉴权（EXPORT 再判定，任一拒绝→失败，docs/06 §12）
        List<Long> resourceIds = parseResourceIds(job.getResourceSnapshot());
        ReauthResult ra = redecideExport(job, resourceIds);
        if (ra.denied) {
            markFailed(job, finalApproverId, DbErrorCode.AUTH_RESOURCE_DENIED.name(), "执行前重鉴权拒绝：" + ra.reason);
            return;
        }
        // 3. 重解析列脱敏上下文
        Map<String, ColumnMaskingPolicy> columnPolicies = columnPolicyResolver
            .map(r -> r.resolveByTableColumn(resourceIds)).orElse(Map.of());
        Map<String, MaskingLevel> columnUnmaskLevels = resolveColumnUnmask(job, columnPolicies);
        MaskingLevel maskingLevel = parseMaskingLevel(job.getMaskingLevel());

        // 4. 组装请求并执行
        job.setStatus("APPROVED");
        exportJobMapper.updateById(job);
        long maxRows = parseLong(job.getLimits(), "maxRows", 1_000_000L);
        long maxBytes = parseLong(job.getLimits(), "maxBytes", 500L * 1024 * 1024);
        ExportExecutionRequest req = new ExportExecutionRequest(
            job.getId(), job.getApplicantId(), null, null, job.getDataSourceId(),
            job.getDatabaseName(), job.getSchemaName(), sql, resourceIds, job.getDecisionId(),
            maskingLevel, columnPolicies, columnUnmaskLevels, maxRows, maxBytes, 300L);
        ExportResult result;
        try {
            result = exportExecutionGateway.get().execute(req);
        } catch (RuntimeException e) {
            log.warn("导出执行异常 jobId={}", jobId, e);
            markFailed(job, finalApproverId, DbErrorCode.INTERNAL_ERROR.name(), "导出执行异常");
            return;
        }
        if (result == null || result.status() != ExecutionStatus.SUCCEEDED) {
            String ec = result == null ? DbErrorCode.INTERNAL_ERROR.name() : result.errorCode();
            markFailed(job, finalApproverId, ec, "导出执行失败");
            return;
        }
        // 5. 落地结果
        job.setStatus("SUCCEEDED");
        job.setRowCount(result.rowCount());
        job.setResultBytes(result.resultBytes());
        job.setObjectKey(result.objectKey());
        job.setFileHash(result.fileHash());
        job.setEncryptionKeyRef(result.encryptionKeyRef());
        job.setExpiresAt(Date.from(Instant.now().plusSeconds(OBJECT_TTL_SECONDS)));
        job.setDownloadCount(0);
        exportJobMapper.updateById(job);
        audit(job, finalApproverId, AuditResult.SUCCESS, "EXPORT_APPROVAL_EXECUTED", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRejection(Long jobId, Long approverId, String reason) {
        DbExportJob job = exportJobMapper.selectById(jobId);
        if (job == null || !"PENDING_APPROVAL".equals(job.getStatus())) {
            return;
        }
        job.setStatus("REJECTED");
        exportJobMapper.updateById(job);
        audit(job, approverId, AuditResult.FAILURE, "EXPORT_REJECTED", reason);
    }

    // ====================== 内部 ======================

    private void markFailed(DbExportJob job, Long approverId, String errorCode, String reason) {
        job.setStatus("FAILED");
        exportJobMapper.updateById(job);
        audit(job, approverId, AuditResult.FAILURE, "EXPORT_EXECUTE_FAILED", errorCode + ":" + reason);
    }

    private ReauthResult redecideExport(DbExportJob job, List<Long> resourceIds) {
        for (Long rid : resourceIds) {
            DecisionRequest dr = new DecisionRequest(job.getApplicantId(), null, null, rid, DbAction.EXPORT, Map.of());
            try {
                AccessDecision d = decisionService.decide(dr);
                if (!d.allowed()) {
                    return new ReauthResult(true, d.reasonCode());
                }
            } catch (RuntimeException e) {
                return new ReauthResult(true, "DECISION_ERROR");
            }
        }
        return new ReauthResult(false, null);
    }

    private Map<String, MaskingLevel> resolveColumnUnmask(DbExportJob job, Map<String, ColumnMaskingPolicy> columnPolicies) {
        Map<String, MaskingLevel> out = new HashMap<>();
        for (Map.Entry<String, ColumnMaskingPolicy> e : columnPolicies.entrySet()) {
            ColumnMaskingPolicy cp = e.getValue();
            if (cp == null || !cp.isSensitive() || cp.resourceId() == null) {
                continue;
            }
            try {
                DecisionRequest dr = new DecisionRequest(job.getApplicantId(), null, null, cp.resourceId(), DbAction.COLUMN_UNMASK, Map.of());
                AccessDecision d = decisionService.decide(dr);
                if (d.allowed()) {
                    out.put(e.getKey(), MaskingLevel.UNMASKED);
                }
            } catch (RuntimeException ignored) {
                // 保持掩码
            }
        }
        return out;
    }

    private void audit(DbExportJob job, Long actorId, AuditResult result, String action, String reason) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.EXPORT, action, actorId, Map.of(),
                "DATA_SOURCE", String.valueOf(job.getDataSourceId()), Map.of(),
                result, null, null, null,
                Map.of("jobId", String.valueOf(job.getId()), "status", job.getStatus(),
                    "reason", reason == null ? "" : reason)));
        } catch (Exception e) {
            log.warn("导出审计写入失败 jobId={}", job.getId(), e);
        }
    }

    static List<Long> parseResourceIds(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshotJson);
            com.fasterxml.jackson.databind.JsonNode arr = n.get("resourceIds");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode x : arr) {
                ids.add(x.asLong());
            }
            return ids;
        } catch (Exception e) {
            return List.of();
        }
    }

    static Long parseApprover(String snapshotJson, String key) {
        if (snapshotJson == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshotJson);
            return n.has(key) ? n.get(key).asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static MaskingLevel parseMaskingLevel(String s) {
        if (s == null) return MaskingLevel.MASKED;
        try { return MaskingLevel.valueOf(s); } catch (Exception e) { return MaskingLevel.MASKED; }
    }

    static long parseLong(String limitsJson, String key, long def) {
        if (limitsJson == null) return def;
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(limitsJson);
            return n.has(key) ? n.get(key).asLong() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private record ReauthResult(boolean denied, String reason) {
    }
}
