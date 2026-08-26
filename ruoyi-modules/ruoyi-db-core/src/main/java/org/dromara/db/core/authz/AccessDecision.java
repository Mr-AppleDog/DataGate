package org.dromara.db.core.authz;

import java.util.List;

/**
 * 授权判定响应（docs/03 第 7.4 节）。
 *
 * <p>面向内部使用；面向普通用户的响应不得泄露无权资源名称或其他人的授权详情。</p>
 *
 * @param decisionId    决策 ID（注入 {@link org.dromara.db.core.domain.ExecutionPlan#decisionId()}）
 * @param allowed       是否允许（显式拒绝优先、默认拒绝）
 * @param reasonCode    原因码（如 ALLOW_BY_APPROVAL_GRANT、DENY_BY_EXPLICIT、DEFAULT_DENY）
 * @param grantIds      命中的授权 ID
 * @param limits        最终限制（拒绝时为 null）
 * @param policyVersion 策略版本（缓存键含此版本；变更广播失效）
 * @author DataGate
 */
public record AccessDecision(
    String decisionId,
    boolean allowed,
    String reasonCode,
    List<String> grantIds,
    DecisionLimits limits,
    long policyVersion
) {

    public AccessDecision {
        grantIds = grantIds == null ? List.of() : List.copyOf(grantIds);
    }
}
