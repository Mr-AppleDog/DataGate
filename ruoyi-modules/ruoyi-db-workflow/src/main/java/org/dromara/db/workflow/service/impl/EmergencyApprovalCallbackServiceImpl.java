package org.dromara.db.workflow.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.service.GrantAdminService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.SubjectType;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.domain.DbEmergencyAccess;
import org.dromara.db.workflow.mapper.DbEmergencyAccessMapper;
import org.dromara.db.workflow.service.EmergencyApprovalCallbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 紧急访问审批回调实现（docs/03 §10.4，M5-04）。
 *
 * <p>双人审批通过 → 生成 EMERGENCY 来源临时授权（≤2h，requireMfa+requireRecentReauth 二次认证）+
 * 即时通知（best-effort 日志+审计，钉钉/邮件通道由 alert 模块接通）+ 标记复盘待办（开通后 24h）。
 * 拒绝/撤销不生成授权。幂等：非 PENDING_APPROVAL 不重复处理。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyApprovalCallbackServiceImpl implements EmergencyApprovalCallbackService {

    private final DbEmergencyAccessMapper emergencyAccessMapper;
    private final GrantAdminService grantAdminService;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onApproval(Long accessId, Long finalApproverId) {
        DbEmergencyAccess access = emergencyAccessMapper.selectById(accessId);
        if (access == null) {
            log.warn("紧急访问回调：工单不存在 {}", accessId);
            return;
        }
        if (!"PENDING_APPROVAL".equals(access.getStatus()) && !"APPROVED".equals(access.getStatus())) {
            return; // 幂等
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(DbWorkflowConstants.EMERGENCY_MAX_VALID_SECONDS);
        // 构造紧急临时授权
        Grant grant = new Grant();
        grant.setSubjectType(SubjectType.USER);
        grant.setSubjectId(access.getApplicantId());
        grant.setResourceId(access.getTargetResourceId());
        grant.setAction(parseAction(access.getTargetAction()));
        grant.setEffect(GrantEffect.ALLOW);
        // 强制 TOTP（requireMfa）+ 5min 内二次认证（requireRecentReauth），docs/03 §6/§10.4
        grant.setConditions(Map.of("requireMfa", true, "requireRecentReauth", 300));
        grant.setEffectiveAt(now);
        grant.setExpiresAt(expiresAt);
        grant.setSourceType(GrantSourceType.EMERGENCY);
        grant.setSourceId(access.getId());
        grant.setReason("紧急访问：" + access.getEventNo() + " / " + access.getReason());
        Long grantId = grantAdminService.createGrant(grant, finalApproverId);

        access.setStatus("ACTIVE");
        access.setGrantId(grantId);
        access.setValidFrom(java.util.Date.from(now));
        access.setValidUntil(java.util.Date.from(expiresAt));
        access.setPostMortemDueAt(java.util.Date.from(now.plusSeconds(DbWorkflowConstants.POST_MORTEM_DEADLINE_SECONDS)));
        emergencyAccessMapper.updateById(access);
        // 即时通知（best-effort；通道投递由 alert 模块接通）
        log.info("紧急访问授权已开通 accessId={} grantId={} eventNo={} 申请人={} 有效期至 {}",
            accessId, grantId, access.getEventNo(), access.getApplicantId(), expiresAt);
        audit(access, finalApproverId, AuditResult.SUCCESS, "EMERGENCY_GRANTED", "grantId=" + grantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRejection(Long accessId, Long approverId, String reason) {
        DbEmergencyAccess access = emergencyAccessMapper.selectById(accessId);
        if (access == null || !"PENDING_APPROVAL".equals(access.getStatus())) {
            return;
        }
        access.setStatus("REJECTED");
        emergencyAccessMapper.updateById(access);
        audit(access, approverId, AuditResult.FAILURE, "EMERGENCY_REJECTED", reason);
    }

    private static DbAction parseAction(String s) {
        try {
            return DbAction.valueOf(s);
        } catch (Exception e) {
            return DbAction.QUERY;
        }
    }

    private void audit(DbEmergencyAccess access, Long actorId, AuditResult result, String action, String reason) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.AUTH, action, actorId, Map.of(),
                "RESOURCE", String.valueOf(access.getTargetResourceId()), Map.of(),
                result, null, null, null,
                Map.of("accessId", String.valueOf(access.getId()), "eventNo", access.getEventNo(),
                    "status", access.getStatus(), "reason", reason == null ? "" : reason)));
        } catch (Exception e) {
            log.warn("紧急访问审计写入失败 accessId={}", access.getId(), e);
        }
    }
}
