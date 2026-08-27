package org.dromara.db.workflow.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.service.GrantAdminService;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.workflow.domain.GrantApplication;
import org.dromara.db.workflow.repository.GrantApplicationRepository;
import org.dromara.db.workflow.service.GrantApprovalCallbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批回调实现（M2-02）。批准生成 Grant，拒绝不生成。
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantApprovalCallbackServiceImpl implements GrantApprovalCallbackService {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_REJECTED = "REJECTED";

    private final GrantApplicationRepository applicationRepository;
    private final GrantAdminService grantAdminService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onApproval(Long applicationId, Long approverId) {
        GrantApplication app = applicationRepository.findById(applicationId);
        if (app == null) {
            log.warn("审批回调：申请单不存在 {}", applicationId);
            return;
        }
        if (!STATUS_PENDING.equals(app.getStatus())) {
            // 幂等：已处理不再重复生成授权
            return;
        }
        Grant grant = toGrant(app);
        Long grantId = grantAdminService.createGrant(grant, approverId);
        applicationRepository.updateApprovalResult(applicationId, STATUS_APPROVED, grantId, approverId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRejection(Long applicationId, Long approverId, String reason) {
        GrantApplication app = applicationRepository.findById(applicationId);
        if (app == null || !STATUS_PENDING.equals(app.getStatus())) {
            return;
        }
        applicationRepository.updateApprovalResult(applicationId, STATUS_REJECTED, null, approverId);
    }

    /**
     * 申请单→授权（来源 REQUEST，sourceId 关联申请单用于幂等）。
     */
    private static Grant toGrant(GrantApplication app) {
        Grant grant = new Grant();
        grant.setSubjectType(app.getSubjectType());
        grant.setSubjectId(app.getSubjectId());
        grant.setResourceId(app.getResourceId());
        grant.setAction(app.getAction());
        grant.setEffect(app.getEffect());
        grant.setConditions(app.getConditions());
        grant.setEffectiveAt(app.getEffectiveAt());
        grant.setExpiresAt(app.getExpiresAt());
        grant.setSourceType(GrantSourceType.REQUEST);
        grant.setSourceId(app.getId());
        grant.setReason(app.getReason());
        return grant;
    }
}
