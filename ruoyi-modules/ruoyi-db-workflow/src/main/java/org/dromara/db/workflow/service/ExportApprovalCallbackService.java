package org.dromara.db.workflow.service;

/**
 * 导出审批回调（docs/03 §10.2、docs/06 §12）。
 *
 * <p>两级审批通过后触发执行前重鉴权 + 流式导出 + 加密对象落地；拒绝/撤销不执行。
 *
 * @author DataGate
 */
public interface ExportApprovalCallbackService {

    /** 两级审批全部通过：执行前重鉴权 → 流式导出 → 加密对象 → 落地结果（幂等） */
    void onApproval(Long jobId, Long finalApproverId);

    /** 拒绝/撤销/终止：标记 REJECTED/CANCELED，不执行导出（幂等） */
    void onRejection(Long jobId, Long approverId, String reason);
}
