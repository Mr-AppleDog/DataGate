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

    /** 导出审批流程编码（V15 种子，dbg_export_approval，两级：Owner→DBA） */
    public static final String FLOW_CODE_EXPORT_APPROVAL = "dbg_export_approval";

    /** 变更审批流程编码（V16 种子，dbg_change_approval，两级：业务负责人→DBA） */
    public static final String FLOW_CODE_CHANGE_APPROVAL = "dbg_change_approval";

    /** 紧急访问审批流程编码（V17 种子，dbg_emergency_approval，双人审批） */
    public static final String FLOW_CODE_EMERGENCY_APPROVAL = "dbg_emergency_approval";

    /** 紧急访问审批节点编码 */
    public static final String NODE_APPROVE1 = "approve1";
    public static final String NODE_APPROVE2 = "approve2";

    /** 紧急访问最长有效期 2h（docs/03 §10.4，不续期） */
    public static final long EMERGENCY_MAX_VALID_SECONDS = 2 * 3600L;
    /** 事后复盘时限 24h */
    public static final long POST_MORTEM_DEADLINE_SECONDS = 24 * 3600L;

    /** 变更审批节点编码（业务负责人；DBA 复用 NODE_DBA_APPROVE） */
    public static final String NODE_BIZ_APPROVE = "biz_approve";

    /** 导出审批节点编码 */
    public static final String NODE_OWNER_APPROVE = "owner_approve";
    public static final String NODE_DBA_APPROVE = "dba_approve";

    /** 审批节点编码 */
    public static final String NODE_APPROVE = "approve";

    /** 流程变量键：PASS:approve = 目标审批人 userId（WarmFlow assignment listener 锁定办理人） */
    public static final String VAR_APPROVE_NODE = "PASS:" + NODE_APPROVE;

    /** 导出两级审批流程变量：分别锁定 Owner / DBA 审批人 */
    public static final String VAR_OWNER_APPROVE = "PASS:" + NODE_OWNER_APPROVE;
    public static final String VAR_DBA_APPROVE = "PASS:" + NODE_DBA_APPROVE;

    /** 变更两级审批流程变量：分别锁定 业务负责人 / DBA */
    public static final String VAR_BIZ_APPROVE = "PASS:" + NODE_BIZ_APPROVE;
    public static final String VAR_DBA_APPROVE_CHANGE = "PASS:" + NODE_DBA_APPROVE;

    /** 紧急访问双人审批流程变量：分别锁定审批人1/2 */
    public static final String VAR_APPROVE1 = "PASS:" + NODE_APPROVE1;
    public static final String VAR_APPROVE2 = "PASS:" + NODE_APPROVE2;

    /** 申请单状态 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_CANCELED = "CANCELED";

    /** 固定系统租户 */
    public static final String TENANT_ID = "000000";
}
