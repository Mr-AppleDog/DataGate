package org.dromara.db.workflow.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.event.ProcessEvent;
import org.dromara.common.core.enums.BusinessStatusEnum;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.service.GrantApprovalCallbackService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * WarmFlow 流程事件监听（M2-02）。
 *
 * <p>监听 {@link ProcessEvent}（由 WorkflowGlobalListener.finish 同步发布，在 completeTask 调用栈内）。
 * 当流程编码为查询权限审批且状态为 FINISH → 批准回调生成 Grant；
 * TERMINATION/INVALID → 拒绝回调（兜底，正常 reject/cancel 已主动回调）。</p>
 *
 * <p>幂等：回调实现自身幂等（非 PENDING 不重复处理）。</p>
 *
 * @author DataGate
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrantApprovalEventListener {

    private final GrantApprovalCallbackService callbackService;

    @EventListener
    public void onProcessEvent(ProcessEvent event) {
        if (!DbWorkflowConstants.FLOW_CODE_QUERY_GRANT.equals(event.getFlowCode())) {
            return;
        }
        Long applicationId = parseLong(event.getBusinessId());
        if (applicationId == null) {
            return;
        }
        String status = event.getStatus();
        if (BusinessStatusEnum.FINISH.getStatus().equals(status)) {
            // 审批人办理（completeTask）触发，仍在审批人请求上下文
            Long approverId = LoginHelper.getUserId();
            log.info("查询权限审批通过，触发授权生成：applicationId={}, approverId={}", applicationId, approverId);
            callbackService.onApproval(applicationId, approverId);
        } else if (BusinessStatusEnum.TERMINATION.getStatus().equals(status)
            || BusinessStatusEnum.INVALID.getStatus().equals(status)) {
            Long approverId = LoginHelper.getUserId();
            log.info("查询权限审批终止/作废，不生成授权：applicationId={}, status={}", applicationId, status);
            callbackService.onRejection(applicationId, approverId, "流程" + status);
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
