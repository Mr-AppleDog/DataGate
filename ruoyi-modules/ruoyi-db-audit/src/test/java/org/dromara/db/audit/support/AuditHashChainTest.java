package org.dromara.db.audit.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 审计哈希链计算测试（AUD-006）
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("审计哈希链")
class AuditHashChainTest {

    @Test
    @DisplayName("同一输入哈希稳定；任一字段变化哈希改变")
    void hashIsDeterministicAndSensitive() {
        Instant now = Instant.parse("2026-08-26T10:15:30Z");
        String h1 = AuditHashChain.computeEventHash(
            "e-1", "CONFIG", "DATASOURCE_CREATE", 1L, Map.of("username", "admin"),
            "DATA_SOURCE", "100", Map.of("name", "生产库"), "SUCCESS", "10.0.0.1", "t-1",
            Map.of("k", "v"), now, AuditHashChain.GENESIS);
        String h2 = AuditHashChain.computeEventHash(
            "e-1", "CONFIG", "DATASOURCE_CREATE", 1L, Map.of("username", "admin"),
            "DATA_SOURCE", "100", Map.of("name", "生产库"), "SUCCESS", "10.0.0.1", "t-1",
            Map.of("k", "v"), now, AuditHashChain.GENESIS);
        assertEquals(h1, h2);
        assertEquals(64, h1.length());

        // 篡改 action 字段
        String tampered = AuditHashChain.computeEventHash(
            "e-1", "CONFIG", "DATASOURCE_DELETE", 1L, Map.of("username", "admin"),
            "DATA_SOURCE", "100", Map.of("name", "生产库"), "SUCCESS", "10.0.0.1", "t-1",
            Map.of("k", "v"), now, AuditHashChain.GENESIS);
        assertNotEquals(h1, tampered);

        // 篡改前驱哈希
        String tamperedPrev = AuditHashChain.computeEventHash(
            "e-1", "CONFIG", "DATASOURCE_CREATE", 1L, Map.of("username", "admin"),
            "DATA_SOURCE", "100", Map.of("name", "生产库"), "SUCCESS", "10.0.0.1", "t-1",
            Map.of("k", "v"), now, "0".repeat(64));
        assertNotEquals(h1, tamperedPrev);
    }

    @Test
    @DisplayName("Map 键序不影响哈希（规范序列化）")
    void mapKeyOrderInsensitive() {
        Instant now = Instant.parse("2026-08-26T10:15:30Z");
        Map<String, Object> a = Map.of("x", 1, "y", 2);
        Map<String, Object> b = Map.of("y", 2, "x", 1);
        String ha = AuditHashChain.computeEventHash("e", "C", "A", 1L, a, "T", "1", null,
            "SUCCESS", null, null, null, now, AuditHashChain.GENESIS);
        String hb = AuditHashChain.computeEventHash("e", "C", "A", 1L, b, "T", "1", null,
            "SUCCESS", null, null, null, now, AuditHashChain.GENESIS);
        assertEquals(ha, hb);
    }

    @Test
    @DisplayName("分片键为 UTC 日")
    void chainKeyIsUtcDay() {
        assertEquals("20260826", AuditHashChain.chainKeyOf(Instant.parse("2026-08-26T00:00:00Z")));
        assertEquals("20260826", AuditHashChain.chainKeyOf(Instant.parse("2026-08-26T23:59:59Z")));
    }
}
