package org.dromara.db.observability.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 慢查询归一化引擎测试（docs/07 §5.1）：双指纹稳定、字面量折叠、敏感清理、PARSE_FAILED、Redis 命令模板。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("慢查询归一化引擎")
class SlowQueryNormalizerTest {

    private final SlowQueryNormalizer normalizer = new SlowQueryNormalizer();

    @Test
    @DisplayName("不同字面量归一化后双指纹一致（portableFingerprint 稳定）")
    void literalNormalizationStable() {
        var r1 = normalizer.normalize("MYSQL", "SELECT * FROM orders WHERE id = 1 AND status = 'NEW'");
        var r2 = normalizer.normalize("MYSQL", "SELECT * FROM orders WHERE id = 999 AND status = 'PAID'");
        assertEquals(r1.fingerprint(), r2.fingerprint(),
            "等价 SQL（仅常量不同）必须产生相同 portableFingerprint");
        assertTrue(r1.normalizedStatement().contains("?"),
            "字面量必须替换为占位符");
        assertFalse(r1.normalizedStatement().contains("NEW"),
            "字符串字面量不得保留在归一化模板中");
    }

    @Test
    @DisplayName("IN 列表被检测并标记")
    void inListDetected() {
        var r = normalizer.normalize("MYSQL", "SELECT * FROM t WHERE id IN (1, 2, 3, 4, 5)");
        assertTrue(r.riskFlags().contains("inList"),
            "riskFlags 应标记检测到 IN 列表");
    }

    @Test
    @DisplayName("密码赋值字面量被清理（日志/通知永不发原文）")
    void passwordSanitized() {
        var r = normalizer.normalize("MYSQL", "SELECT * FROM u WHERE password = 'secret123'");
        assertFalse(r.normalizedStatement().contains("secret123"),
            "归一化模板不得含明文密码");
        assertFalse(r.sanitizedSample().contains("secret123"),
            "脱敏样例不得含明文密码");
    }

    @Test
    @DisplayName("邮箱/手机/Bearer/Token 敏感字面量被清理")
    void sensitiveLiteralsSanitized() {
        String raw = "SELECT * FROM u WHERE password = 'p@ssw0rd' AND email = 'admin@datagate.com' AND phone = '13800138000'";
        var r = normalizer.normalize("MYSQL", raw);
        assertFalse(r.sanitizedSample().contains("p@ssw0rd"),
            "明文密码不得出现在脱敏样例");
        assertFalse(r.sanitizedSample().contains("admin@datagate.com"),
            "邮箱不得出现在脱敏样例");
        assertFalse(r.sanitizedSample().contains("13800138000"),
            "手机号不得出现在脱敏样例");
    }

    @Test
    @DisplayName("空输入走 PARSE_FAILED 且指纹非空")
    void emptyInputParseFailed() {
        var r = normalizer.normalize("MYSQL", "");
        assertEquals("PARSE_FAILED", r.ingestQuality());
        assertFalse(r.fingerprint().isBlank());
    }

    @Test
    @DisplayName("无法解析的输入不抛异常且产出非空指纹")
    void unparseableInputDoesNotThrow() {
        var r = normalizer.normalize("MYSQL", "@@@ this is not ### valid sql $$$ at all");
        assertFalse(r.fingerprint().isBlank(),
            "解析失败仍须产出保守指纹，治理不丢失");
        assertFalse(r.normalizedStatement().isBlank());
    }

    @Test
    @DisplayName("Redis 命令模板归一化：保留命令名与参数个数，不保存 value")
    void redisCommandTemplate() {
        var r = normalizer.normalize("REDIS", "GET mykey");
        assertEquals("GET [argc=1]", r.normalizedStatement());
        assertTrue(r.riskFlags().contains("argc"),
            "riskFlags 应记录 Redis 参数个数");
        assertFalse(r.normalizedStatement().contains("mykey"),
            "命令模板不得保留 key 明文");
    }

    @Test
    @DisplayName("parserVersion 以 druid- 前缀（升级可追溯）")
    void parserVersionPrefixed() {
        var r = normalizer.normalize("POSTGRESQL", "SELECT * FROM t WHERE id = 1");
        assertTrue(r.parserVersion().startsWith("druid-"),
            "parserVersion 用于解析器升级追溯，不得为空");
    }

    @Test
    @DisplayName("PG 方言归一化产出 COMPLETE 与非空指纹")
    void postgresDialectNormalized() {
        var r = normalizer.normalize("POSTGRESQL", "SELECT * FROM public.orders WHERE id = 1");
        assertFalse(r.fingerprint().isBlank());
        assertEquals("COMPLETE", r.ingestQuality());
    }
}
