package org.dromara.db.workflow.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.event.ProcessEvent;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.service.EmergencyApprovalCallbackService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 紧急访问审批流程事件监听（docs/03 §10.4，M5-04）。
 *
 * <p>EMERGENCY_APPROVAL 流程：FINISH → 双人审批通过 → 生成紧急临时授权 + 通知 + 复盘待办；
 * TERMINATION/INVALID → 拒绝/撤销。幂等由回调保证。</p>
 *
 * @author DataGate
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmergencyApprovalEventListener {

    private final EmergencyApprovalCallbackService callbackService;

    @EventListener
    public void onProcessEvent(ProcessEvent event) {
        if (!DbWorkflowConstants.FLOW_CODE_EMERGENCY_APPROVAL.equals(event.getFlowCode())) {
            return;
        }
        Long accessId = parseLong(event.getBusinessId());
        if (accessId == null) {
            return;
        }
        String status = event.getStatus();
        if (BusinessStatusEnum.FINISH.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            log.info("紧急访问双人审批通过，生成临时授权：accessId={}, approverId={}", accessId, approverId);
            callbackService.onApproval(accessId, approverId);
        } else if (BusinessStatusEnum.TERMINATION.getStatus().equals(status)
            || BusinessStatusEnum.INVALID.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            callbackService.onRejection(accessId, approverId, "流程" + status);
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
