package org.dromara.db.auth.service;

import org.dromara.db.auth.InMemoryGrantRepository;
import org.dromara.db.auth.StubHierarchyResolver;
import org.dromara.db.auth.StubMembershipResolver;
import org.dromara.db.auth.config.AuthorizationProperties;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.policy.DefaultPolicyVersionSource;
import org.dromara.db.auth.resolver.ResourceHierarchyResolver;
import org.dromara.db.auth.resolver.SubjectMembershipResolver;
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.SubjectType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权判定引擎单元测试（docs/03 第 13 节必测场景 + M2-01 要求项，AUTH-001~004）。
 *
 * <p>纯单元测试：内存 grant fixture + 桩解析器，不依赖数据库与 Spring 上下文。</p>
 *
 * @author DataGate
 */
@Tag("unit")
class AuthorizationDecisionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Long ACTOR = 1001L;
    private static final Long DEPT = 103L;
    private static final Long GROUP = 200L;
    private static final Long RES_DS = 1L;
    private static final Long RES_DB = 2L;
    private static final Long RES_TBL = 3L;
    private static final Long RES_TBL2 = 4L;

    private static AuthorizationDecisionServiceImpl service(
        InMemoryGrantRepository repo,
        Optional<ResourceHierarchyResolver> hierarchy,
        Optional<SubjectMembershipResolver> membership
    ) {
        return new AuthorizationDecisionServiceImpl(
            repo, hierarchy, membership,
            new DefaultPolicyVersionSource(repo),
            AuthorizationProperties.productionDefaults(),
            Optional.empty() // 无缓存：保留即时重读授权表语义，覆盖 §13 判定算法
        );
    }

    // ===== §13 #1：显式拒绝优先（数据库级 ALLOW + 表级 DENY → 表查询拒绝） =====
    @Test
    void explicitDenyPriority_overridesAncestorAllow() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_DB, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L))
            .add(deny(11, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, 1L));
        StubHierarchyResolver hr = new StubHierarchyResolver().put(RES_TBL, List.of(RES_TBL, RES_DB));
        var svc = service(repo, Optional.of(hr), Optional.empty());

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DENY_BY_EXPLICIT, d.reasonCode());
        assertNull(d.limits());
        assertTrue(d.grantIds().contains("11"));
    }

    // ===== 默认拒绝（无任何授权 → DEFAULT_DENY；亦覆盖 §13 #8 DBA 无显式授权） =====
    @Test
    void defaultDeny_whenNoGrants() {
        var svc = service(new InMemoryGrantRepository(), Optional.of(selfChain(RES_TBL)), Optional.empty());
        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
        assertNull(d.limits());
    }

    // ===== §13 继承：祖先资源上的授权对后代生效 =====
    @Test
    void inheritance_ancestorAllowAppliesToDescendant() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_DB, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L));
        StubHierarchyResolver hr = new StubHierarchyResolver().put(RES_TBL, List.of(RES_TBL, RES_DB));
        var svc = service(repo, Optional.of(hr), Optional.empty());

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertTrue(d.allowed());
        assertEquals(DecisionReasonCodes.ALLOW_BY_DIRECT_GRANT, d.reasonCode());
        assertTrue(d.grantIds().contains("10"));
    }

    // ===== §13 #7 到期：已过期 ALLOW 不生效 → 默认拒绝 =====
    @Test
    void expiredAllow_doesNotSatisfy() {
        Grant g = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.REQUEST, 1L);
        g.setExpiresAt(NOW.minusSeconds(3600));
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(g);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
        assertNull(d.limits());
    }

    // ===== §13 #2 跨部门：用户经 dept 授权可访问 =====
    @Test
    void crossDept_deptGrantAllowsAccess() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_TBL, DbAction.QUERY, SubjectType.DEPT, DEPT, GrantSourceType.MANUAL, 1L));
        StubMembershipResolver mr = new StubMembershipResolver().put(ACTOR, Set.of(DEPT), Set.of());
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.of(mr));

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertTrue(d.allowed());
        assertEquals(DecisionReasonCodes.ALLOW_BY_DIRECT_GRANT, d.reasonCode());
    }

    // ===== §13 #2 退出部门：权限立即失效 =====
    @Test
    void crossDept_userLeavesDept_permissionInvalidated() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_TBL, DbAction.QUERY, SubjectType.DEPT, DEPT, GrantSourceType.MANUAL, 1L));
        // 用户已退出部门 → 无 dept 归属
        StubMembershipResolver mr = new StubMembershipResolver().put(ACTOR, Set.of(), Set.of());
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.of(mr));

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
    }

    // ===== §13 环境限制合并：多条 ALLOW 取较小值，与环境硬上限取较小值 =====
    @Test
    void environmentLimitsMerge_takesMinOfGrantsAndEnvHardCeiling() {
        // 两条 ALLOW：maxRows 1000 与 200 → 取较小 200
        Grant a = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        a.setConditions(Map.of("maxRows", 1000));
        Grant b = allow(11, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        b.setConditions(Map.of("maxRows", 200));
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(a).add(b);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertTrue(d.allowed());
        assertNotNull(d.limits());
        assertEquals(200L, d.limits().maxRows());
        assertEquals(MaskingLevel.MASKED, d.limits().maskingLevel());
    }

    @Test
    void environmentLimitsMerge_envHardCeilingCapsGrantLimit() {
        // 授权 maxRows 10000 超过环境硬上限 5000 → 取 5000
        Grant a = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        a.setConditions(Map.of("maxRows", 10000));
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(a);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        assertTrue(d.allowed());
        assertEquals(5000L, d.limits().maxRows());
    }

    @Test
    void environmentLimitsMerge_defaultsWhenGrantSpecifiesNothing() {
        Grant a = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(a);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);

        // 授权未指定 → 环境默认 500 行 / 10MB / 30s
        assertEquals(500L, d.limits().maxRows());
        assertEquals(10485760L, d.limits().maxBytes());
        assertEquals(30L, d.limits().maxExecutionSeconds());
    }

    // ===== §13 并发旧缓存：policyVersion 变更后旧决策不命中 =====
    @Test
    void staleCache_policyVersionChangeInvalidatesOldKey() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L));
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        DecisionRequest req = request(RES_TBL, DbAction.QUERY);
        AccessDecision before = svc.doDecide(req, NOW);
        assertEquals(1L, before.policyVersion());

        // 策略变更：新增授权令 policy_version 递增
        repo.add(allow(11, RES_TBL2, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 2L));
        AccessDecision after = svc.doDecide(req, NOW);
        assertEquals(2L, after.policyVersion());
        assertNotEquals(before.policyVersion(), after.policyVersion());

        // 缓存键含 policyVersion → 旧键不命中新决策
        String ctxHash = DecisionCacheKey.contextHash(req.requestContext());
        String keyBefore = DecisionCacheKey.build(ACTOR, RES_TBL, DbAction.QUERY, ctxHash, before.policyVersion());
        String keyAfter = DecisionCacheKey.build(ACTOR, RES_TBL, DbAction.QUERY, ctxHash, after.policyVersion());
        assertNotEquals(keyBefore, keyAfter);
    }

    // ===== §13 条件不满足的 ALLOW 视为不满足 =====
    @Test
    void conditionUnsatisfied_allowNotSatisfied() {
        Grant g = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        g.setConditions(Map.of("sourceIpCidr", "10.0.0.0/8"));
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(g);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        // 来源 IP 不在 10/8 → 条件不满足 → 默认拒绝
        DecisionRequest req = new DecisionRequest(ACTOR, "sess", "192.168.1.1", RES_TBL, DbAction.QUERY, Map.of());
        AccessDecision d = svc.doDecide(req, NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
    }

    @Test
    void conditionSatisfied_ipInCidr_allows() {
        Grant g = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        g.setConditions(Map.of("sourceIpCidr", "10.0.0.0/8"));
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(g);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        DecisionRequest req = new DecisionRequest(ACTOR, "sess", "10.5.6.7", RES_TBL, DbAction.QUERY, Map.of());
        AccessDecision d = svc.doDecide(req, NOW);
        assertTrue(d.allowed());
    }

    // ===== 撤销/未生效的授权不参与判定（失败关闭语义） =====
    @Test
    void revokedGrant_ignored_defaultsDeny() {
        Grant g = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        g.setRevokedAt(NOW.minusSeconds(60));
        var svc = service(new InMemoryGrantRepository().add(g), Optional.of(selfChain(RES_TBL)), Optional.empty());
        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
    }

    @Test
    void notYetEffectiveGrant_ignored_defaultsDeny() {
        Grant g = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        g.setEffectiveAt(NOW.plusSeconds(3600));
        var svc = service(new InMemoryGrantRepository().add(g), Optional.of(selfChain(RES_TBL)), Optional.empty());
        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
    }

    // ===== §13 #3 查询权与导出权分离（QUERY 允许、EXPORT 默认拒绝） =====
    @Test
    void queryAllowed_exportDeniedWithoutExportGrant() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L));
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        AccessDecision q = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);
        assertTrue(q.allowed());

        AccessDecision e = svc.doDecide(request(RES_TBL, DbAction.EXPORT), NOW);
        assertFalse(e.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, e.reasonCode());
    }

    // ===== 来源 reason code：审批 vs 直接 =====
    @Test
    void approvalGrantReason_vs_directGrantReason() {
        Grant req = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.REQUEST, 1L);
        var svcReq = service(new InMemoryGrantRepository().add(req), Optional.of(selfChain(RES_TBL)), Optional.empty());
        assertEquals(DecisionReasonCodes.ALLOW_BY_APPROVAL_GRANT,
            svcReq.doDecide(request(RES_TBL, DbAction.QUERY), NOW).reasonCode());

        Grant man = allow(11, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        var svcMan = service(new InMemoryGrantRepository().add(man), Optional.of(selfChain(RES_TBL)), Optional.empty());
        assertEquals(DecisionReasonCodes.ALLOW_BY_DIRECT_GRANT,
            svcMan.doDecide(request(RES_TBL, DbAction.QUERY), NOW).reasonCode());
    }

    // ===== 失败关闭：操作人缺失 =====
    @Test
    void failClosed_nullActor_denied() {
        var svc = service(new InMemoryGrantRepository(), Optional.of(selfChain(RES_TBL)), Optional.empty());
        DecisionRequest req = new DecisionRequest(null, "sess", "10.0.0.1", RES_TBL, DbAction.QUERY, Map.of());
        AccessDecision d = svc.doDecide(req, NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DENY_SUBJECT_INVALID, d.reasonCode());
    }

    // ===== 失败关闭：资源不可解析 =====
    @Test
    void failClosed_resourceUnresolved_denied() {
        // 解析器存在但对未知资源返回空 → 失败关闭
        StubHierarchyResolver hr = new StubHierarchyResolver().put(RES_TBL, List.of(RES_TBL, RES_DB));
        var svc = service(new InMemoryGrantRepository(), Optional.of(hr), Optional.empty());
        DecisionRequest req = new DecisionRequest(ACTOR, "sess", "10.0.0.1", 9999L, DbAction.QUERY, Map.of());
        AccessDecision d = svc.doDecide(req, NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DENY_RESOURCE_UNRESOLVED, d.reasonCode());
    }

    // ===== null-safe 回退：无解析器时只查资源自身（不实现继承，但仍默认拒绝安全） =====
    @Test
    void noResolver_fallsBackToSelfOnly_allowsSelfGrant() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L));
        var svc = service(repo, Optional.empty(), Optional.empty());
        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);
        assertTrue(d.allowed());
    }

    @Test
    void noResolver_fallsBackToSelfOnly_ancestorGrantNotApplied() {
        // 无解析器：祖先上的授权不应命中后代（不实现继承）
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_DB, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L));
        var svc = service(repo, Optional.empty(), Optional.empty());
        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.QUERY), NOW);
        assertFalse(d.allowed());
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, d.reasonCode());
    }

    // ===== COLUMN_UNMASK 授权 → UNMASKED 脱敏级别 =====
    @Test
    void columnUnmask_allowed_returnsUnmasked() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository()
            .add(allow(10, RES_TBL, DbAction.COLUMN_UNMASK, SubjectType.USER, ACTOR, GrantSourceType.REQUEST, 1L));
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());
        AccessDecision d = svc.doDecide(request(RES_TBL, DbAction.COLUMN_UNMASK), NOW);
        assertTrue(d.allowed());
        assertEquals(MaskingLevel.UNMASKED, d.limits().maskingLevel());
    }

    // ===== explicit DENY 条件不满足时不触发（DENY 也需条件匹配） =====
    @Test
    void denyWithUnsatisfiedCondition_doesNotTrigger_allowWins() {
        Grant allow = allow(10, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, GrantSourceType.MANUAL, 1L);
        Grant deny = deny(11, RES_TBL, DbAction.QUERY, SubjectType.USER, ACTOR, 1L);
        deny.setConditions(Map.of("sourceIpCidr", "10.0.0.0/8")); // 仅限 10/8
        InMemoryGrantRepository repo = new InMemoryGrantRepository().add(allow).add(deny);
        var svc = service(repo, Optional.of(selfChain(RES_TBL)), Optional.empty());

        // 来源 IP 不在 10/8：DENY 条件不满足 → 不触发；ALLOW 无条件 → 命中
        DecisionRequest req = new DecisionRequest(ACTOR, "sess", "192.168.1.1", RES_TBL, DbAction.QUERY, Map.of());
        AccessDecision d = svc.doDecide(req, NOW);
        assertTrue(d.allowed());
        assertEquals(DecisionReasonCodes.ALLOW_BY_DIRECT_GRANT, d.reasonCode());
    }

    // ===== helpers =====

    private static DecisionRequest request(Long resourceId, DbAction action) {
        return new DecisionRequest(ACTOR, "sess", "127.0.0.1", resourceId, action, Map.of());
    }

    private static Grant allow(long id, Long res, DbAction action, SubjectType st, Long sid, GrantSourceType src, long pv) {
        return base(id, res, action, GrantEffect.ALLOW, st, sid, src, pv);
    }

    private static Grant deny(long id, Long res, DbAction action, SubjectType st, Long sid, long pv) {
        return base(id, res, action, GrantEffect.DENY, st, sid, GrantSourceType.MANUAL, pv);
    }

    private static Grant base(long id, Long res, DbAction action, GrantEffect effect,
                              SubjectType st, Long sid, GrantSourceType src, long pv) {
        Grant g = new Grant();
        g.setId(id);
        g.setTenantId("000000");
        g.setSubjectType(st);
        g.setSubjectId(sid);
        g.setResourceId(res);
        g.setAction(action);
        g.setEffect(effect);
        g.setSourceType(src);
        g.setPolicyVersion(pv);
        g.setDelFlag("0");
        return g;
    }

    private static StubHierarchyResolver selfChain(Long res) {
        return new StubHierarchyResolver().put(res, List.of(res));
    }
}
