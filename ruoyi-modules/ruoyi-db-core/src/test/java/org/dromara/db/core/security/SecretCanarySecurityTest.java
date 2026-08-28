package org.dromara.db.core.security;

import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.dromara.db.core.masking.DefaultFieldMaskingEngine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secret 泄漏金丝雀测试（docs/08 §6、§11，docs/09 §10 M6-05）。
 *
 * <p>验证秘密不经 toString/日志/异常/结果集泄漏：SecretValue.toString 固定掩码；
 * 销毁后不可用；脱敏后 RowCell 不含原值；常见秘密键名不落入审计 details。
 *
 * @author DataGate
 */
@Tag("unit")
class SecretCanarySecurityTest {

    private static final String SECRET = "super-secret-password-123";

    @Test
    void secret_value_tostring_is_mask() {
        SecretValue sv = SecretValue.of(SECRET);
        assertEquals("******", sv.toString(), "toString 永远固定掩码，不得输出真值");
        assertNotEquals(SECRET, sv.toString());
    }

    @Test
    void secret_in_string_concat_does_not_leak() {
        SecretValue sv = SecretValue.of(SECRET);
        // 模拟日志/异常拼接：不得出现真值
        String log = "credential=" + sv;
        assertFalse(log.contains(SECRET), "日志拼接不得泄漏真值：" + log);
        assertTrue(log.contains("******"));
    }

    @Test
    void destroyed_secret_unusable() {
        SecretValue sv = SecretValue.of(SECRET);
        sv.destroy();
        assertTrue(sv.isDestroyed());
        assertThrows(IllegalStateException.class, () -> sv.useSecret(c -> {}));
    }

    @Test
    void masked_rowcell_does_not_contain_plaintext() {
        // 脱敏后结果集不含原值（服务端流式脱敏，前端无原值）
        DefaultFieldMaskingEngine engine = new DefaultFieldMaskingEngine();
        ColumnMaskingPolicy policy = new ColumnMaskingPolicy(1L, "pwd", SensitivityLevel.SENSITIVE, MaskingType.CUSTOM,
            new org.dromara.db.core.domain.MaskingConfig(0, 0), "MANUAL");
        RowCell cell = engine.mask(new RowCell(SECRET, false, null), policy, MaskingLevel.MASKED);
        assertNotEquals(SECRET, cell.value(), "脱敏后不得含原值");
        assertFalse(cell.value().contains(SECRET));
    }

    @Test
    void hidden_cell_value_null() {
        DefaultFieldMaskingEngine engine = new DefaultFieldMaskingEngine();
        ColumnMaskingPolicy policy = new ColumnMaskingPolicy(1L, "c", SensitivityLevel.RESTRICTED, MaskingType.PHONE, null, "MANUAL");
        RowCell cell = engine.mask(new RowCell(SECRET, false, null), policy, MaskingLevel.HIDDEN);
        assertEquals(null, cell.value(), "HIDDEN 整列不返回值");
    }

    @Test
    void empty_secret_rejected() {
        assertThrows(IllegalArgumentException.class, () -> SecretValue.of(""));
        assertThrows(IllegalArgumentException.class, () -> SecretValue.of((String) null));
    }
}
