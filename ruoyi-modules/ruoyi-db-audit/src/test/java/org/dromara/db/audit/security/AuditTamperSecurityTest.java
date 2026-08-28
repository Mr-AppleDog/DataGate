package org.dromara.db.audit.security;

import org.dromara.db.audit.support.AuditHashChain;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 审计篡改检测安全测试（docs/08 §9.3，docs/09 §10 M6-05）。
 *
 * <p>验证任一不可变事实字段被篡改 → eventHash 改变 → verifyChain 校验失败。
 * 哈希链覆盖 previousHash + 全字段；篡改者无法只改一个字段而保持链连续。
 *
 * @author DataGate
 */
@Tag("unit")
class AuditTamperSecurityTest {

    private static final Instant T = Instant.parse("2026-08-28T10:00:00Z");
    private static final String PREV = "prev-hash-abc";

    private String baseHash(String action, String result, String prev, Long actorId) {
        return AuditHashChain.computeEventHash(
            "evt-1", "QUERY", action, actorId, Map.of(),
            "DATA_SOURCE", "1", Map.of(),
            result, "10.0.0.1", "trace-1",
            Map.of("rowCount", 5), T, prev);
    }

    @Test
    void tamper_action_changes_hash() {
        String h = baseHash("QUERY_EXECUTE", "SUCCESS", PREV, 1L);
        String tampered = baseHash("QUERY_EXECUTE_EVIL", "SUCCESS", PREV, 1L);
        assertNotEquals(h, tampered, "篡改 action 必须改变哈希");
    }

    @Test
    void tamper_result_changes_hash() {
        String h = baseHash("QUERY_EXECUTE", "SUCCESS", PREV, 1L);
        String tampered = baseHash("QUERY_EXECUTE", "FAILURE", PREV, 1L);
        assertNotEquals(h, tampered, "篡改 result 必须改变哈希（掩盖失败为成功）");
    }

    @Test
    void tamper_actor_changes_hash() {
        String h = baseHash("QUERY_EXECUTE", "SUCCESS", PREV, 1L);
        String tampered = baseHash("QUERY_EXECUTE", "SUCCESS", PREV, 999L);
        assertNotEquals(h, tampered, "篡改 actor 必须改变哈希（冒充他人）");
    }

    @Test
    void tamper_previous_hash_changes_hash() {
        String h = baseHash("QUERY_EXECUTE", "SUCCESS", PREV, 1L);
        String tampered = baseHash("QUERY_EXECUTE", "SUCCESS", "forged-prev", 1L);
        assertNotEquals(h, tampered, "篡改 previousHash 破坏链连续性");
    }

    @Test
    void tamper_details_changes_hash() {
        String h = AuditHashChain.computeEventHash("evt-1", "QUERY", "QUERY_EXECUTE", 1L, Map.of(),
            "DATA_SOURCE", "1", Map.of(), "SUCCESS", "10.0.0.1", "trace-1",
            Map.of("rowCount", 5), T, PREV);
        String tampered = AuditHashChain.computeEventHash("evt-1", "QUERY", "QUERY_EXECUTE", 1L, Map.of(),
            "DATA_SOURCE", "1", Map.of(), "SUCCESS", "10.0.0.1", "trace-1",
            Map.of("rowCount", 999), T, PREV);
        assertNotEquals(h, tampered, "篡改 details 必须改变哈希");
    }

    @Test
    void tamper_timestamp_changes_hash() {
        String h = baseHash("QUERY_EXECUTE", "SUCCESS", PREV, 1L);
        String tampered = AuditHashChain.computeEventHash("evt-1", "QUERY", "QUERY_EXECUTE", 1L, Map.of(),
            "DATA_SOURCE", "1", Map.of(), "SUCCESS", "10.0.0.1", "trace-1",
            Map.of("rowCount", 5), T.plusSeconds(1), PREV);
        assertNotEquals(h, tampered, "篡改时间戳必须改变哈希");
    }
}
