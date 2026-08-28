package org.dromara.db.workflow.service;

/**
 * 紧急访问审批回调（docs/03 §10.4，M5-04）。
 *
 * <p>双人审批通过 → 生成 EMERGENCY 来源临时授权（≤2h，requireMfa+requireRecentReauth）+
 * 即时通知 + 标记复盘待办（开通后 24h）。拒绝/撤销不生成。
 *
 * @author DataGate
 */
public interface EmergencyApprovalCallbackService {

    /** 双人审批通过：生成紧急临时授权 + 通知 + 复盘待办（幂等） */
    void onApproval(Long accessId, Long finalApproverId);

    /** 拒绝/撤销/终止：标记 REJECTED/CANCELED，不生成授权（幂等） */
    void onRejection(Long accessId, Long approverId, String reason);
}
