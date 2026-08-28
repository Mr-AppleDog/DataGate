package org.dromara.db.workflow.service;

/**
 * 变更审批回调（docs/03 §10.3、M5-02c）。
 *
 * <p>两级审批通过后标记 APPROVED（执行由 execute() 在执行窗口内触发，非自动）。
 * 拒绝/撤销标记 REJECTED/CANCELED。
 *
 * @author DataGate
 */
public interface ChangeApprovalCallbackService {

    /** 两级审批全部通过：标记 APPROVED（幂等） */
    void onApproval(Long orderId, Long finalApproverId);

    /** 拒绝/撤销/终止：标记 REJECTED（幂等） */
    void onRejection(Long orderId, Long approverId, String reason);
}
