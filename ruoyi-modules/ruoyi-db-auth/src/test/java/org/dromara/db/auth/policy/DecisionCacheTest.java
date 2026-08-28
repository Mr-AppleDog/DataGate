package org.dromara.db.auth.policy;

import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.enums.DbAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DecisionCache 单元测试（docs/03 第 8 节、docs/06 §16 撤权 60s）。
 *
 * <p>覆盖：TTL 命中/过期、onPolicyChanged 清空、onUserInvalidated 按用户清理、
 * TTL 硬上限 60s 裁剪。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("DecisionCache TTL 与失效链路 (§16)")
class DecisionCacheTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    private static AccessDecision allow() {
        return new AccessDecision(UUID.randomUUID().toString(), true, "ALLOW",
            List.of("g1"), null, 5L);
    }

    private static AccessDecision deny() {
        return new AccessDecision(UUID.randomUUID().toString(), false, "DEFAULT_DENY",
            List.of(), null, 5L);
    }

    @Test
    @DisplayName("TTL 内命中，过期后 miss")
    void hitBeforeTtlMissAfter() {
        DecisionCache c = new DecisionCache(30);
        c.put("authz:1:3:QUERY:0:v5", allow(), NOW);
        assertTrue(c.get("authz:1:3:QUERY:0:v5", NOW).isPresent(), "TTL 内命中");
        assertTrue(c.get("authz:1:3:QUERY:0:v5", NOW.plusSeconds(29)).isPresent(), "29s 仍命中");
        assertFalse(c.get("authz:1:3:QUERY:0:v5", NOW.plusSeconds(31)).isPresent(), "31s 过期 miss");
    }

    @Test
    @DisplayName("onPolicyChanged 即时清空全部缓存")
    void onPolicyChangedClearsAll() {
        DecisionCache c = new DecisionCache(30);
        c.put("authz:1:3:QUERY:0:v5", allow(), NOW);
        c.put("authz:2:4:QUERY:0:v5", allow(), NOW);
        assertEquals(2, c.size());
        c.onPolicyChanged(6L);
        assertEquals(0, c.size(), "撤权/变更后缓存清空");
        assertFalse(c.get("authz:1:3:QUERY:0:v5", NOW).isPresent());
    }

    @Test
    @DisplayName("onUserInvalidated 按用户清理（保留其他用户）")
    void onUserInvalidatedPrunesUser() {
        DecisionCache c = new DecisionCache(30);
        c.put("authz:1:3:QUERY:0:v5", allow(), NOW);
        c.put("authz:1:4:QUERY:0:v5", deny(), NOW);
        c.put("authz:2:3:QUERY:0:v5", allow(), NOW);
        c.onUserInvalidated(1L);
        assertEquals(1, c.size(), "仅剩用户 2 的缓存");
        assertTrue(c.get("authz:2:3:QUERY:0:v5", NOW).isPresent(), "用户 2 保留");
        assertFalse(c.get("authz:1:3:QUERY:0:v5", NOW).isPresent(), "用户 1 已清理");
    }

    @Test
    @DisplayName("onUserInvalidated(null) 清空全部")
    void onUserInvalidatedNullClearsAll() {
        DecisionCache c = new DecisionCache(30);
        c.put("authz:1:3:QUERY:0:v5", allow(), NOW);
        c.onUserInvalidated(null);
        assertEquals(0, c.size());
    }

    @Test
    @DisplayName("TTL 硬上限 60s 裁剪：超 60s 仍为 60，负值降为 1")
    void ttlCappedAt60() {
        assertEquals(60L, new DecisionCache(120).ttlSeconds(), "120→60");
        assertEquals(60L, new DecisionCache(60).ttlSeconds(), "60→60");
        assertEquals(30L, new DecisionCache(30).ttlSeconds(), "30→30");
        assertEquals(1L, new DecisionCache(0).ttlSeconds(), "0→1");
        assertEquals(1L, new DecisionCache(-5).ttlSeconds(), "负→1");
    }

    @Test
    @DisplayName("version-in-key：不同版本不命中（即时失效语义）")
    void versionInKeyDifferentVersionMisses() {
        DecisionCache c = new DecisionCache(30);
        c.put("authz:1:3:QUERY:0:v5", allow(), NOW);
        Optional<AccessDecision> hitV5 = c.get("authz:1:3:QUERY:0:v5", NOW);
        Optional<AccessDecision> missV6 = c.get("authz:1:3:QUERY:0:v6", NOW);
        assertTrue(hitV5.isPresent(), "v5 命中");
        assertFalse(missV6.isPresent(), "v6 不命中（版本变更即失效）");
    }

    @Test
    @DisplayName("null key/value 不缓存不抛")
    void nullSafe() {
        DecisionCache c = new DecisionCache(30);
        c.put(null, allow(), NOW);
        c.put("k", null, NOW);
        assertEquals(0, c.size());
        assertFalse(c.get(null, NOW).isPresent());
    }
}
