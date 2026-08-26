package org.dromara.db.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.dromara.db.auth.config.AuthorizationProperties;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.repository.GrantRepository;
import org.dromara.db.auth.resolver.ResourceHierarchyResolver;
import org.dromara.db.auth.resolver.SubjectMembership;
import org.dromara.db.auth.resolver.SubjectMembershipResolver;
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.AuthorizationDecisionService;
import org.dromara.db.core.authz.DecisionLimits;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.MaskingLevel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 资源授权判定实现（docs/03 第 7 节，AUTH-001~004，M2-01）。
 *
 * <p>逐条实现 docs/03 第 7.2 节判定算法：默认拒绝、显式拒绝优先、失败关闭；
 * 限制合并取“所选授权路径限制”与“环境硬上限”的较小值（docs/03 第 7.3 节）。</p>
 *
 * <p>跨模块依赖以可选端口注入（不硬依赖 db-resource / ruoyi-system）：
 * {@link ResourceHierarchyResolver} 缺省时只查资源自身（不实现继承）；
 * {@link SubjectMembershipResolver} 缺省时仅 USER 直接授权生效。</p>
 *
 * <p>本切片职责边界：TOTP/账号状态完整校验、RuoYi 功能权限校验由 console 负责（步骤 1/2 仅留接口位与非空校验）；
 * 字段级脱敏（HIDDEN）依赖 db-resource 列画像，本切片仅按动作判定 UNMASKED/MASKED。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
public class AuthorizationDecisionServiceImpl implements AuthorizationDecisionService {

    private final GrantRepository grantRepository;
    private final Optional<ResourceHierarchyResolver> hierarchyResolver;
    private final Optional<SubjectMembershipResolver> membershipResolver;
    private final org.dromara.db.auth.policy.PolicyVersionSource policyVersionSource;
    private final AuthorizationProperties properties;

    public AuthorizationDecisionServiceImpl(
        GrantRepository grantRepository,
        Optional<ResourceHierarchyResolver> hierarchyResolver,
        Optional<SubjectMembershipResolver> membershipResolver,
        org.dromara.db.auth.policy.PolicyVersionSource policyVersionSource,
        AuthorizationProperties properties
    ) {
        this.grantRepository = grantRepository;
        this.hierarchyResolver = hierarchyResolver;
        this.membershipResolver = membershipResolver;
        this.policyVersionSource = policyVersionSource;
        this.properties = properties;
    }

    @Override
    public AccessDecision decide(DecisionRequest request) {
        return doDecide(request, Instant.now());
    }

    /**
     * 可注入当前时间的判定入口（纯单元测试用，避免依赖系统时钟）。
     */
    AccessDecision doDecide(DecisionRequest request, Instant now) {
        long policyVersion = safePolicyVersion();
        try {
            return decideInternal(request, now, policyVersion);
        } catch (Exception e) {
            // 失败关闭：判定过程任何异常均拒绝而非放行（docs/03 第 1.1、7.2 节）
            log.warn("authorization decision failed (fail-closed): actor={} resource={} action={} reason={}",
                request.actorId(), request.resourceId(), request.action(), e.toString());
            return deny(DecisionReasonCodes.DENY_DECISION_ERROR, List.of(), policyVersion);
        }
    }

    private AccessDecision decideInternal(DecisionRequest request, Instant now, long policyVersion) {
        // 步骤 1：校验操作人（TOTP/账号状态完整链路由 console 负责，本切片仅非空校验）
        if (request.actorId() == null) {
            return deny(DecisionReasonCodes.DENY_SUBJECT_INVALID, List.of(), policyVersion);
        }
        // 步骤 2：RuoYi 功能权限校验——由 console 负责（本切片留接口位，不阻断）
        // 步骤 3：资源存在/可发现——经资源层级解析器校验非空
        if (request.resourceId() == null || request.action() == null) {
            return deny(DecisionReasonCodes.DENY_RESOURCE_UNRESOLVED, List.of(), policyVersion);
        }
        List<Long> ancestors = resolveAncestors(request.resourceId());
        if (ancestors == null || ancestors.isEmpty()) {
            // 解析器存在但返回空：资源不可解析 → 失败关闭
            return deny(DecisionReasonCodes.DENY_RESOURCE_UNRESOLVED, List.of(), policyVersion);
        }
        // 步骤 4：加载候选授权（资源+祖先上的 user/dept/role/group 候选）
        SubjectMembership membership = resolveMembership(request.actorId());
        List<Grant> candidates = grantRepository.findCandidates(
            ancestors, request.action(), request.actorId(),
            membership.deptIds(), membership.groupIds());

        // 步骤 5：过滤未开始/已过期/已撤销/主体不匹配（主体不匹配已在查询层过滤；时效在此过滤）
        List<Grant> active = new ArrayList<>();
        for (Grant g : candidates) {
            if (isActive(g, now)) {
                active.add(g);
            }
        }

        // 步骤 6：显式拒绝优先——存在匹配动作+条件的 DENY 即立即拒绝
        for (Grant g : active) {
            if (g.getEffect() == GrantEffect.DENY && ConditionEvaluator.satisfied(g, request, now)) {
                return deny(DecisionReasonCodes.DENY_BY_EXPLICIT, List.of(grantId(g)), policyVersion);
            }
        }

        // 步骤 7：独立评估每条 ALLOW；至少一条完整满足才允许
        List<Grant> satisfiedAllows = new ArrayList<>();
        for (Grant g : active) {
            if (g.getEffect() == GrantEffect.ALLOW && ConditionEvaluator.satisfied(g, request, now)) {
                satisfiedAllows.add(g);
            }
        }
        if (satisfiedAllows.isEmpty()) {
            // 默认拒绝：无任何完整满足的 ALLOW（已过期 ALLOW 在步骤 5 被过滤 → 亦归此码）
            return deny(DecisionReasonCodes.DEFAULT_DENY, List.of(), policyVersion);
        }

        // 步骤 8：限制合并——取所选授权路径限制与环境硬上限的较小值（docs/03 第 7.3 节）
        DecisionLimits limits = mergeLimits(satisfiedAllows, request.action());

        // 步骤 9：字段脱敏级别（docs/03 第 7.4 节；HIDDEN 依赖列画像，本切片按动作判定）
        // 步骤 10：返回判定响应
        String reasonCode = pickAllowReason(satisfiedAllows);
        List<String> grantIds = satisfiedAllows.stream().map(g -> grantId(g)).toList();
        return new AccessDecision(
            UUID.randomUUID().toString(), true, reasonCode, grantIds, limits, policyVersion);
    }

    private List<Long> resolveAncestors(Long resourceId) {
        // 解析器缺省：回退为只查资源自身（不实现继承，已在报告中标注）。
        // 解析器存在但返回 null/空：视为资源不可解析 → 由调用方失败关闭（不在此回退）。
        if (hierarchyResolver.isEmpty()) {
            return List.of(resourceId);
        }
        return hierarchyResolver.get().resolveAncestors(resourceId);
    }

    private SubjectMembership resolveMembership(Long actorId) {
        // 解析器缺省：仅 USER 直接授权生效；解析器返回 null 时按空归属处理（默认拒绝安全）。
        if (membershipResolver.isEmpty()) {
            return SubjectMembership.empty(actorId);
        }
        SubjectMembership m = membershipResolver.get().resolve(actorId);
        return m == null ? SubjectMembership.empty(actorId) : m;
    }

    private long safePolicyVersion() {
        try {
            return policyVersionSource.currentVersion();
        } catch (Exception e) {
            log.warn("policy version read failed, fallback to 0: {}", e.toString());
            return 0L;
        }
    }

    /**
     * 授权在 now 时刻是否生效：未撤销、已生效、未过期。
     */
    private static boolean isActive(Grant g, Instant now) {
        if (g.getRevokedAt() != null) {
            return false;
        }
        if (g.getEffectiveAt() != null && now.isBefore(g.getEffectiveAt())) {
            return false;
        }
        return g.getExpiresAt() == null || now.isBefore(g.getExpiresAt());
    }

    /**
     * 限制合并（docs/03 第 7.3 节）：多条 ALLOW 取各维度较小值，再与环境硬上限取较小值；
     * 授权未指定该维度时使用环境默认值。
     */
    private DecisionLimits mergeLimits(List<Grant> satisfiedAllows, DbAction action) {
        long rows = minOfCondition(satisfiedAllows, "maxRows", properties.envDefaultMaxRows());
        long bytes = minOfCondition(satisfiedAllows, "maxBytes", properties.envDefaultMaxBytes());
        long secs = minOfCondition(satisfiedAllows, "maxExecutionSeconds", properties.envDefaultMaxExecutionSeconds());

        long finalRows = Math.min(rows, properties.envHardMaxRows());
        long finalBytes = Math.min(bytes, properties.envHardMaxBytes());
        long finalSecs = Math.min(secs, properties.envHardMaxExecutionSeconds());

        // 脱敏级别：COLUMN_UNMASK 已授权 → UNMASKED；否则默认 MASKED（HIDDEN 依赖列画像，后续切片）
        MaskingLevel masking = (action == DbAction.COLUMN_UNMASK) ? MaskingLevel.UNMASKED : MaskingLevel.MASKED;
        return new DecisionLimits(finalRows, finalBytes, finalSecs, masking);
    }

    /**
     * 取满足的 ALLOW 中指定条件键的最小值；无授权指定时返回 defaultVal。
     */
    private static long minOfCondition(List<Grant> grants, String key, long defaultVal) {
        long result = Long.MAX_VALUE;
        boolean any = false;
        for (Grant g : grants) {
            Map<String, Object> c = g.getConditions();
            if (c == null) {
                continue;
            }
            Object v = c.get(key);
            Long n = toLongOrNull(v);
            if (n != null) {
                any = true;
                result = Math.min(result, n);
            }
        }
        return any ? result : defaultVal;
    }

    private static String pickAllowReason(List<Grant> satisfiedAllows) {
        for (Grant g : satisfiedAllows) {
            if (g.getSourceType() == GrantSourceType.REQUEST) {
                return DecisionReasonCodes.ALLOW_BY_APPROVAL_GRANT;
            }
        }
        return DecisionReasonCodes.ALLOW_BY_DIRECT_GRANT;
    }

    private static String grantId(Grant g) {
        return g.getId() == null ? "0" : String.valueOf(g.getId());
    }

    private static AccessDecision deny(String reasonCode, List<String> grantIds, long policyVersion) {
        return new AccessDecision(
            UUID.randomUUID().toString(), false, reasonCode, grantIds, null, policyVersion);
    }

    private static Long toLongOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
