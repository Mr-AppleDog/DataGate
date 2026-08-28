package org.dromara.db.auth.policy;

import lombok.extern.slf4j.Slf4j;
import org.dromara.db.core.authz.AccessDecision;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权判定决策缓存（docs/03 第 8 节、docs/06 §16 撤权 60s 生效）。
 *
 * <p>节点级内存缓存，键含 {@code policyVersion}（{@link org.dromara.db.auth.service.DecisionCacheKey}）：
 * 权限变更（撤权/新增授权）递增版本后，旧版本键不再命中 → 即时失效（同节点）。
 * TTL（≤60s）作为跨节点保底：即使他节点策略版本读取存在短暂滞后，缓存条目在 TTL 内自然过期，
 * 下次判定重读授权表 → 撤销的授权被 isActive 过滤 → 默认拒绝。两者叠加满足 docs/06 §16
 * 「权限撤销后 60 秒内所有节点生效，新查询无法继续」。</p>
 *
 * <p>同时实现 {@link PolicyCacheInvalidationHook}：权限变更 {@code onPolicyChanged} 即时清空本节点缓存
 * （释放内存 + 跨节点广播位），用户禁用/退出 {@code onUserInvalidated} 按 actor 清理会话级缓存。
 * 缓存写入失败不阻断判定（降级为每次重读授权表）。</p>
 *
 * <p>不缓存密码/SQL/结果正文；缓存值仅为不可变 {@link AccessDecision}（含 decisionId/reasonCode/
 * limits/policyVersion），不泄露底层凭据。</p>
 *
 * @author DataGate
 */
@Slf4j
public class DecisionCache implements PolicyCacheInvalidationHook {

    /** 撤权生效硬上限 60 秒（docs/06 §16）。 */
    static final long MAX_TTL_SECONDS = 60L;

    private final long ttlSeconds;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    /** 单条缓存条目：决策 + 过期时刻。 */
    private record Entry(AccessDecision decision, Instant expiresAt) {
    }

    public DecisionCache(long ttlSeconds) {
        // 强制 TTL ≤ 60s（撤权生效硬上限）；下限 1s 避免无意义缓存
        this.ttlSeconds = Math.min(Math.max(ttlSeconds, 1L), MAX_TTL_SECONDS);
    }

    /** 当前 TTL（秒，已裁剪至 ≤60）。 */
    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 取缓存命中且未过期的决策；过期或缺失返回 empty 并惰性清除。
     */
    public Optional<AccessDecision> get(String key, Instant now) {
        if (key == null) {
            return Optional.empty();
        }
        Entry e = cache.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (now.isAfter(e.expiresAt)) {
            cache.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e.decision());
    }

    /**
     * 写入决策缓存，过期时刻 = now + TTL。
     */
    public void put(String key, AccessDecision decision, Instant now) {
        if (key == null || decision == null) {
            return;
        }
        cache.put(key, new Entry(decision, now.plusSeconds(ttlSeconds)));
    }

    /** 当前缓存条目数（监控/测试用）。 */
    public int size() {
        return cache.size();
    }

    // ====================== PolicyCacheInvalidationHook ======================

    /**
     * 策略版本变更（撤权/新增授权）即时清空本节点缓存。
     * version-in-key 已令旧键永不命中，此处仅为释放内存与跨节点广播位。
     */
    @Override
    public void onPolicyChanged(long newVersion) {
        cache.clear();
        log.debug("policy version bumped to {}, decision cache cleared ({} entries)", newVersion, cache.size());
    }

    /**
     * 用户级失效（禁用/退出/离职/部门变动）：删除该用户会话级缓存（docs/03 第 8 节）。
     */
    @Override
    public void onUserInvalidated(Long actorId) {
        if (actorId == null) {
            cache.clear();
            return;
        }
        String prefix = "authz:" + actorId + ":";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("user {} invalidated, decision cache pruned", actorId);
    }
}
