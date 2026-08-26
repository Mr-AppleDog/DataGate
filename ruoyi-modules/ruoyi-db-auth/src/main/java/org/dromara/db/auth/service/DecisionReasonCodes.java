package org.dromara.db.auth.service;

/**
 * 授权判定原因码（docs/03 第 7.4 节，AUTH-001~004）。
 *
 * <p>允许：{@link #ALLOW_BY_APPROVAL_GRANT}（审批工单生成的 ALLOW）、
 * {@link #ALLOW_BY_DIRECT_GRANT}（人工/系统/紧急直接 ALLOW）。
 * 拒绝：{@link #DENY_BY_EXPLICIT}（显式拒绝优先）、{@link #DEFAULT_DENY}（默认拒绝）、
 * {@link #DENY_SUBJECT_INVALID}/{@link #DENY_RESOURCE_UNRESOLVED}/{@link #DENY_DECISION_ERROR}
 * （失败关闭）。</p>
 *
 * @author DataGate
 */
public final class DecisionReasonCodes {

    private DecisionReasonCodes() {
    }

    /** 审批工单（source=REQUEST）生成的 ALLOW 命中 */
    public static final String ALLOW_BY_APPROVAL_GRANT = "ALLOW_BY_APPROVAL_GRANT";

    /** 人工/系统/紧急直接 ALLOW 命中 */
    public static final String ALLOW_BY_DIRECT_GRANT = "ALLOW_BY_DIRECT_GRANT";

    /** 显式拒绝优先：匹配动作+条件的 DENY 命中 */
    public static final String DENY_BY_EXPLICIT = "DENY_BY_EXPLICIT";

    /** 默认拒绝：无任何完整满足的 ALLOW（含已过期 ALLOW 不生效） */
    public static final String DEFAULT_DENY = "DEFAULT_DENY";

    /** 失败关闭：操作人缺失（TOTP/账号状态完整校验由 console 负责，本切片仅非空校验） */
    public static final String DENY_SUBJECT_INVALID = "DENY_SUBJECT_INVALID";

    /** 失败关闭：资源不可解析（不存在/已下线/解析异常） */
    public static final String DENY_RESOURCE_UNRESOLVED = "DENY_RESOURCE_UNRESOLVED";

    /** 失败关闭：判定过程异常，拒绝而非放行 */
    public static final String DENY_DECISION_ERROR = "DENY_DECISION_ERROR";
}
