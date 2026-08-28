package org.dromara.db.workflow.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.event.ProcessEvent;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.service.ExportApprovalCallbackService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 导出审批流程事件监听（docs/03 §10.2、M5-01c）。
 *
 * <p>监听 EXPORT_APPROVAL 流程事件：FINISH → 两级审批通过 → 执行前重鉴权+流式导出；
 * TERMINATION/INVALID → 拒绝/撤销，不执行导出。幂等由回调实现保证。</p>
 *
 * @author DataGate
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportApprovalEventListener {

    private final ExportApprovalCallbackService callbackService;

    @EventListener
    public void onProcessEvent(ProcessEvent event) {
        if (!DbWorkflowConstants.FLOW_CODE_EXPORT_APPROVAL.equals(event.getFlowCode())) {
            return;
        }
        Long jobId = parseLong(event.getBusinessId());
        if (jobId == null) {
            return;
        }
        String status = event.getStatus();
        if (BusinessStatusEnum.FINISH.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            log.info("导出审批通过，触发执行：jobId={}, approverId={}", jobId, approverId);
            callbackService.onApproval(jobId, approverId);
        } else if (BusinessStatusEnum.TERMINATION.getStatus().equals(status)
            || BusinessStatusEnum.INVALID.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            callbackService.onRejection(jobId, approverId, "流程" + status);
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
