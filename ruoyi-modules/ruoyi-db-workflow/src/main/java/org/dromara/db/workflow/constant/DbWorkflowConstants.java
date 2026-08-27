package org.dromara.db.workflow.constant;

/**
 * DataGate 工单流程常量（M2-02，docs/03 §10.1、docs/10 M2-02）。
 *
 * @author DataGate
 */
public final class DbWorkflowConstants {

    private DbWorkflowConstants() {
    }

    /** 查询权限审批流程编码（V10 种子，dbg_query_grant） */
    public static final String FLOW_CODE_QUERY_GRANT = "dbg_query_grant";

    /** 审批节点编码 */
    public static final String NODE_APPROVE = "approve";

    /** 流程变量键：PASS:approve = 目标审批人 userId（WarmFlow assignment listener 锁定办理人） */
    public static final String VAR_APPROVE_NODE = "PASS:" + NODE_APPROVE;

    /** 申请单状态 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_CANCELED = "CANCELED";

    /** 固定系统租户 */
    public static final String TENANT_ID = "000000";
}
