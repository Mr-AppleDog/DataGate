package org.dromara.db.workflow.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.workflow.domain.DbChangeOrder;
import org.dromara.db.workflow.mapper.DbChangeOrderMapper;
import org.dromara.db.workflow.service.ChangeApprovalCallbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 变更审批回调实现（docs/03 §10.3、M5-02c）。
 *
 * <p>两级审批通过 → APPROVED（执行由 execute() 在窗口内触发，非自动）；拒绝 → REJECTED。
 * 幂等：非 PENDING_APPROVAL 不重复处理。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeApprovalCallbackServiceImpl implements ChangeApprovalCallbackService {

    private final DbChangeOrderMapper changeOrderMapper;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onApproval(Long orderId, Long finalApproverId) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        if (order == null) {
            log.warn("变更回调：工单不存在 {}", orderId);
            return;
        }
        if (!"PENDING_APPROVAL".equals(order.getStatus())) {
            return; // 幂等
        }
        order.setStatus("APPROVED");
        changeOrderMapper.updateById(order);
        audit(order, finalApproverId, AuditResult.SUCCESS, "CHANGE_APPROVED", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRejection(Long orderId, Long approverId, String reason) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        if (order == null || !"PENDING_APPROVAL".equals(order.getStatus())) {
            return;
        }
        order.setStatus("REJECTED");
        changeOrderMapper.updateById(order);
        audit(order, approverId, AuditResult.FAILURE, "CHANGE_REJECTED", reason);
    }

    private void audit(DbChangeOrder order, Long actorId, AuditResult result, String action, String reason) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.CHANGE, action, actorId, Map.of(),
                "DATA_SOURCE", String.valueOf(order.getDataSourceId()), Map.of(),
                result, null, null, null,
                Map.of("orderId", String.valueOf(order.getId()), "status", order.getStatus(),
                    "reason", reason == null ? "" : reason)));
        } catch (Exception e) {
            log.warn("变更审计写入失败 orderId={}", order.getId(), e);
        }
    }
}
