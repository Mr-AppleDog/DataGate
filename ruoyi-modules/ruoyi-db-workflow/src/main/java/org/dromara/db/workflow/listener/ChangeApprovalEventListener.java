package org.dromara.db.workflow.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.event.ProcessEvent;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.service.ChangeApprovalCallbackService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 变更审批流程事件监听（docs/03 §10.3、M5-02c）。
 *
 * <p>CHANGE_APPROVAL 流程：FINISH → 两级审批通过 → 标记 APPROVED（执行由 execute() 触发）；
 * TERMINATION/INVALID → 拒绝/撤销。幂等由回调保证。</p>
 *
 * @author DataGate
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeApprovalEventListener {

    private final ChangeApprovalCallbackService callbackService;

    @EventListener
    public void onProcessEvent(ProcessEvent event) {
        if (!DbWorkflowConstants.FLOW_CODE_CHANGE_APPROVAL.equals(event.getFlowCode())) {
            return;
        }
        Long orderId = parseLong(event.getBusinessId());
        if (orderId == null) {
            return;
        }
        String status = event.getStatus();
        if (BusinessStatusEnum.FINISH.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            log.info("变更审批通过，标记 APPROVED：orderId={}, approverId={}", orderId, approverId);
            callbackService.onApproval(orderId, approverId);
        } else if (BusinessStatusEnum.TERMINATION.getStatus().equals(status)
            || BusinessStatusEnum.INVALID.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            callbackService.onRejection(orderId, approverId, "流程" + status);
        }
    }

    private static Long parseLong(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(businessId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
