package org.dromara.db.auth.service;

import org.dromara.db.auth.InMemoryGrantRepository;
import org.dromara.db.auth.StubHierarchyResolver;
import org.dromara.db.auth.config.AuthorizationProperties;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.policy.DecisionCache;
import org.dromara.db.auth.policy.PolicyVersionSource;
import org.dromara.db.auth.resolver.ResourceHierarchyResolver;
import org.dromara.db.auth.resolver.SubjectMembershipResolver;
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.SubjectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤权 60s 生效切片集成测试（docs/06 §16、docs/03 第 8 节）。
 *
 * <p>验证决策缓存 + 失效链路在撤权后的三种场景：</p>
 * <ol>
 *   <li>版本源新鲜：撤权递增 policyVersion → version-in-key 旧键不命中 → 即时拒绝（同节点）；</li>
 *   <li>版本源滞后（跨节点）：撤权后版本仍读旧值 → 缓存命中陈旧 ALLOW，但 TTL（≤60s）过期后
 *       重读授权表 → 撤销的授权被过滤 → 默认拒绝（60s 内生效）；</li>
 *   <li>失效钩子触发：onPolicyChanged 即时清缓存 → 即使版本源滞后也立即重判拒绝。</li>
 * </ol>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("撤权 60s 生效（决策缓存失效链路）§16")
class AuthorizationRevocationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Long ACTOR = 1001L;
    private static final Long RES = 3L;

    private static DecisionRequest request() {
        return new DecisionRequest(ACTOR, "sess", "127.0.0.1", RES, DbAction.QUERY, Map.of());
    }

    private static Grant allowGrant(long id, long version) {
        Grant g = new Grant();
        g.setId(id);
        g.setTenantId("000000");
        g.setSubjectType(SubjectType.USER);
        g.setSubjectId(ACTOR);
        g.setResourceId(RES);
        g.setAction(DbAction.QUERY);
        g.setEffect(GrantEffect.ALLOW);
        g.setSourceType(GrantSourceType.MANUAL);
        g.setPolicyVersion(version);
        g.setDelFlag("0");
        return g;
    }

    private static AuthorizationDecisionServiceImpl service(
        InMemoryGrantRepository repo, PolicyVersionSource versionSource, DecisionCache cache
    ) {
        Optional<ResourceHierarchyResolver> hr = Optional.of(new StubHierarchyResolver().put(RES, List.of(RES)));
        return new AuthorizationDecisionServiceImpl(
            repo, hr, Optional.empty(), versionSource,
            AuthorizationProperties.productionDefaults(), Optional.of(cache));
    }

    /** 固定版本源（模拟跨节点版本读取滞后）。 */
    private static PolicyVersionSource fixedVersion(long v) {
        return () -> v;
    }

    // ===== #1 版本源新鲜：撤权递增版本 → 即时拒绝 =====

    @Test
    @DisplayName("#1 撤权后版本递增 → version-in-key 旧键不命中 → 即时 DEFAULT_DENY")
    void revokeImmediate_versionInKeyInvalidates() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository();
        Grant g = allowGrant(10, 1L);
        repo.add(g);
        // 版本源读 max（新鲜）
        AuthorizationDecisionServiceImpl svc = service(repo, new org.dromara.db.auth.policy.DefaultPolicyVersionSource(repo), new DecisionCache(30));

        AccessDecision before = svc.doDecide(request(), NOW);
        assertTrue(before.allowed(), "撤权前 ALLOW");
        assertEquals(1L, before.policyVersion());

        // 撤权：置 revokedAt + 递增 policyVersion（模拟 GrantAdminService.revokeGrant 的 updateRevoked）
        g.setRevokedAt(NOW);
        g.setPolicyVersion(2L);

        AccessDecision after = svc.doDecide(request(), NOW);
        assertFalse(after.allowed(), "撤权后新查询必须拒绝");
        assertEquals(2L, after.policyVersion(), "版本已递增");
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, after.reasonCode());
    }

    // ===== #2 版本源滞后（跨节点）：TTL 兜底 → 60s 内拒绝 =====

    @Test
    @DisplayName("#2 撤权后版本源滞后 → TTL（≤60s）过期后重判拒绝（60s 生效）")
    void revokeTtlFallback_staleVersionSource() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository();
        Grant g = allowGrant(10, 1L);
        repo.add(g);
        // 版本源固定返回 1（跨节点滞后）
        AuthorizationDecisionServiceImpl svc = service(repo, fixedVersion(1L), new DecisionCache(30));

        AccessDecision before = svc.doDecide(request(), NOW);
        assertTrue(before.allowed(), "撤权前 ALLOW");

        // 撤权：置 revokedAt（版本源仍返回 1 → 缓存键仍 v1 → 命中陈旧 ALLOW）
        g.setRevokedAt(NOW);

        AccessDecision stale = svc.doDecide(request(), NOW);
        assertTrue(stale.allowed(), "版本滞后 + 缓存未过期 → 暂时仍命中陈旧 ALLOW（staleness 窗口）");

        // TTL 过期后（31s > 30s TTL）→ 缓存 miss → 重读授权表 → 撤销的授权被过滤 → DEFAULT_DENY
        AccessDecision afterTtl = svc.doDecide(request(), NOW.plusSeconds(31));
        assertFalse(afterTtl.allowed(), "TTL 过期后撤权生效（60s 内）");
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, afterTtl.reasonCode());
    }

    @Test
    @DisplayName("#2b TTL 窗口内（29s）仍命中陈旧 ALLOW（证明 staleness 有界于 30s TTL）")
    void revokeTtlFallback_withinTtlStillStale() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository();
        Grant g = allowGrant(10, 1L);
        repo.add(g);
        AuthorizationDecisionServiceImpl svc = service(repo, fixedVersion(1L), new DecisionCache(30));

        svc.doDecide(request(), NOW); // 缓存 ALLOW
        g.setRevokedAt(NOW);
        // 29s 内仍命中（staleness 有界于 30s TTL）
        assertTrue(svc.doDecide(request(), NOW.plusSeconds(29)).allowed(), "29s 仍命中陈旧缓存");
        // 30s 边界：isAfter 严格比较 → NOW+30 不晚于 expiresAt=NOW+30 → 仍命中
        assertTrue(svc.doDecide(request(), NOW.plusSeconds(30)).allowed(), "30s 边界仍命中（严格 >）");
        // 31s 过期 → 重判拒绝
        assertFalse(svc.doDecide(request(), NOW.plusSeconds(31)).allowed(), "31s 过期后撤权生效");
    }

    // ===== #3 失效钩子触发：即时清缓存 → 即使版本源滞后也立即重判 =====

    @Test
    @DisplayName("#3 撤权触发 onPolicyChanged → 清缓存 → 版本源滞后也立即重判拒绝")
    void revokeHookClearsCache_immediateEvenWithStaleVersion() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository();
        Grant g = allowGrant(10, 1L);
        repo.add(g);
        DecisionCache cache = new DecisionCache(30);
        AuthorizationDecisionServiceImpl svc = service(repo, fixedVersion(1L), cache);

        AccessDecision before = svc.doDecide(request(), NOW);
        assertTrue(before.allowed());

        // 撤权：置 revokedAt + 触发失效钩子（GrantAdminService.revokeGrant 会调 onPolicyChanged）
        g.setRevokedAt(NOW);
        cache.onPolicyChanged(2L);

        // 版本源仍返回 1（滞后），但缓存已清 → 重判 → 撤销的授权被过滤 → DEFAULT_DENY
        AccessDecision after = svc.doDecide(request(), NOW);
        assertFalse(after.allowed(), "失效钩子清缓存后立即重判拒绝（即使版本源滞后）");
        assertEquals(DecisionReasonCodes.DEFAULT_DENY, after.reasonCode());
    }

    // ===== 用户级失效 =====

    @Test
    @DisplayName("#4 用户禁用/退出 → onUserInvalidated 清该用户缓存 → 重判拒绝")
    void userInvalidationClearsUserCache() {
        InMemoryGrantRepository repo = new InMemoryGrantRepository();
        Grant g = allowGrant(10, 1L);
        repo.add(g);
        DecisionCache cache = new DecisionCache(30);
        AuthorizationDecisionServiceImpl svc = service(repo, fixedVersion(1L), cache);

        assertTrue(svc.doDecide(request(), NOW).allowed(), "缓存 ALLOW");

        // 用户被禁用/退出 → 清该用户缓存
        cache.onUserInvalidated(ACTOR);

        // 部门/角色变更导致授权不再满足（此处用撤销授权模拟）
        g.setRevokedAt(NOW);
        AccessDecision after = svc.doDecide(request(), NOW);
        assertFalse(after.allowed(), "用户缓存清空后重判 → 撤销授权 → DEFAULT_DENY");
    }

    @Test
    @DisplayName("#5 缓存命中不重复调用判定（性能：grant 查询只触发一次）")
    void cacheHitAvoidsRepeatedGrantQuery() {
        // 通过 InMemoryGrantRepository 计数 findCandidates 调用验证缓存命中
        CountingRepo repo = new CountingRepo();
        repo.add(allowGrant(10, 1L));
        AuthorizationDecisionServiceImpl svc = service(repo, fixedVersion(1L), new DecisionCache(30));

        svc.doDecide(request(), NOW);
        svc.doDecide(request(), NOW);
        svc.doDecide(request(), NOW);
        assertEquals(1, repo.findCandidatesCalls, "缓存命中后不再重复查授权表: " + repo.findCandidatesCalls);
    }

    /** 计数 findCandidates 调用次数的内存仓库。 */
    private static final class CountingRepo extends InMemoryGrantRepository {
        int findCandidatesCalls;

        @Override
        public java.util.List<Grant> findCandidates(java.util.List<Long> resourceIds, DbAction action,
                                                     Long actorId, java.util.Set<Long> deptIds, java.util.Set<Long> groupIds) {
            findCandidatesCalls++;
            return super.findCandidates(resourceIds, action, actorId, deptIds, groupIds);
        }
    }
}
