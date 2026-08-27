package org.dromara.db.workflow.service;

/**
 * 审批结果回调（M2-02，docs/10 M2-02）。
 *
 * <p>由 WarmFlow 审批节点完成时调用：批准→生成 Grant（经 GrantAdminService）；
 * 拒绝不生成（docs/10 M2-02）。幂等：非 PENDING 状态不重复处理。</p>
 *
 * @author DataGate
 */
public interface GrantApprovalCallbackService {

    /**
     * 审批通过：生成授权并回填申请单。
     *
     * @param applicationId 申请单 ID
     * @param approverId     审批人（成为 Grant.createBy）
     */
    void onApproval(Long applicationId, Long approverId);

    /**
     * 审批拒绝：标记申请单 REJECTED，不生成授权。
     *
     * @param applicationId 申请单 ID
     * @param approverId    审批人
     * @param reason        拒绝理由
     */
    void onRejection(Long applicationId, Long approverId, String reason);
}
