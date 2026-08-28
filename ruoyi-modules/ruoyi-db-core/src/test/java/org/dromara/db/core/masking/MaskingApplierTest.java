package org.dromara.db.core.masking;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 流式脱敏应用器单元测试（docs/06 §11、docs/10 M5-05c）。
 *
 * <p>覆盖：敏感列掩码、非敏感列透传、未知来源 prod 隐藏、COLUMN_UNMASK 明文透传、无资源查询透传。
 *
 * @author DataGate
 */
@Tag("unit")
class MaskingApplierTest {

    private final DefaultFieldMaskingEngine engine = new DefaultFieldMaskingEngine();

    private ColumnMaskingPolicy phonePolicy() {
        return new ColumnMaskingPolicy(100L, "phone", SensitivityLevel.SENSITIVE, MaskingType.PHONE, null, "MANUAL");
    }

    private ColumnMaskingPolicy plainPolicy() {
        // 未标注列默认 PUBLIC/NONE
        return new ColumnMaskingPolicy(101L, "name", SensitivityLevel.PUBLIC, MaskingType.NONE, null, null);
    }

    private ExecutionPlan plan(MaskingLevel base, Map<String, ColumnMaskingPolicy> policies, Map<String, MaskingLevel> unmask) {
        return new ExecutionPlan(
            "p1", 1L, 100L, "db", null, "h", "SELECT", "SELECT",
            java.util.List.of(1L), "dec", 500, 10_000_000, 30,
            java.time.Instant.now().minusSeconds(60), java.time.Instant.now().plusSeconds(60),
            base, policies, unmask);
    }

    private RowCell cell(String v) {
        return new RowCell(v, false, null);
    }

    @Test
    void key_is_lowercased_table_dot_column() {
        assertEquals("users.phone", MaskingApplier.key("Users", "Phone"));
        assertEquals(".col", MaskingApplier.key(null, "Col"));
        assertEquals("t.", MaskingApplier.key("T", null));
    }

    @Test
    void sensitive_column_masked_in_prod() {
        ExecutionPlan p = plan(MaskingLevel.MASKED,
            Map.of("users.phone", phonePolicy()), Map.of());
        RowCell out = MaskingApplier.apply(cell("13812345678"), "users", "phone", p, engine);
        assertEquals("138****5678", out.value());
    }

    @Test
    void non_sensitive_direct_column_passthrough_in_prod() {
        // 未标注列有默认 PUBLIC/NONE 策略 → 不误隐藏
        ExecutionPlan p = plan(MaskingLevel.MASKED,
            Map.of("users.name", plainPolicy()), Map.of());
        RowCell out = MaskingApplier.apply(cell("alice"), "users", "name", p, engine);
        assertEquals("alice", out.value());
    }

    @Test
    void unknown_lineage_hidden_in_prod() {
        // 表达式/别名 SELECT sensitive AS x → 基列名匹配不上策略 → 隐藏（防借名绕过）
        ExecutionPlan p = plan(MaskingLevel.MASKED,
            Map.of("users.phone", phonePolicy()), Map.of());
        RowCell out = MaskingApplier.apply(cell("13812345678"), "users", "x", p, engine);
        assertNull(out.value());
    }

    @Test
    void unknown_lineage_passthrough_when_base_unmasked() {
        // 无资源引用查询（SELECT 1）base=UNMASKED → 未知列透传
        ExecutionPlan p = plan(MaskingLevel.UNMASKED, Map.of(), Map.of());
        RowCell out = MaskingApplier.apply(cell("1"), "", "1", p, engine);
        assertEquals("1", out.value());
    }

    @Test
    void column_unmask_grant_returns_plaintext() {
        // 持有 COLUMN_UNMASK 临时授权 + 二次认证通过 → 该列明文
        ExecutionPlan p = plan(MaskingLevel.MASKED,
            Map.of("users.phone", phonePolicy()),
            Map.of("users.phone", MaskingLevel.UNMASKED));
        RowCell out = MaskingApplier.apply(cell("13812345678"), "users", "phone", p, engine);
        assertEquals("13812345678", out.value());
    }

    @Test
    void hidden_base_hides_everything() {
        ExecutionPlan p = plan(MaskingLevel.HIDDEN, Map.of(), Map.of());
        RowCell out = MaskingApplier.apply(cell("secret"), "users", "phone", p, engine);
        assertNull(out.value());
    }

    @Test
    void null_plan_or_engine_passthrough() {
        ExecutionPlan p = plan(MaskingLevel.MASKED, Map.of(), Map.of());
        assertEquals("v", MaskingApplier.apply(cell("v"), "t", "c", null, engine).value());
        assertEquals("v", MaskingApplier.apply(cell("v"), "t", "c", p, null).value());
    }
}
