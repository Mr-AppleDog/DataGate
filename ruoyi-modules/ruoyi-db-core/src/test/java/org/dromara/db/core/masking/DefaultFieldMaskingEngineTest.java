package org.dromara.db.core.masking;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.MaskingConfig;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认脱敏引擎单元测试（docs/10 单元类别"脱敏"、docs/06 §11）。
 *
 * @author DataGate
 */
@Tag("unit")
class DefaultFieldMaskingEngineTest {

    private final DefaultFieldMaskingEngine engine = new DefaultFieldMaskingEngine();

    private ColumnMaskingPolicy policy(MaskingType t) {
        return new ColumnMaskingPolicy(1001L, "col", SensitivityLevel.SENSITIVE, t, null, "MANUAL");
    }

    private RowCell cell(String v) {
        return new RowCell(v, false, null);
    }

    // ---------- 级别语义 ----------

    @Test
    void hidden_level_returns_null_value() {
        RowCell out = engine.mask(cell("13812345678"), policy(MaskingType.PHONE), MaskingLevel.HIDDEN);
        assertNull(out.value());
        assertEquals(false, out.truncated());
        assertNull(out.binarySummary());
    }

    @Test
    void unmasked_level_passthrough() {
        RowCell out = engine.mask(cell("13812345678"), policy(MaskingType.PHONE), MaskingLevel.UNMASKED);
        assertEquals("13812345678", out.value());
    }

    @Test
    void masked_non_sensitive_or_none_type_passthrough() {
        assertEquals("plain", engine.mask(cell("plain"), policy(MaskingType.NONE), MaskingLevel.MASKED).value());
        assertEquals("plain", engine.mask(cell("plain"), null, MaskingLevel.MASKED).value());
        ColumnMaskingPolicy pub = new ColumnMaskingPolicy(1L, "c", SensitivityLevel.PUBLIC, MaskingType.PHONE, null, "RULE");
        assertEquals("plain", engine.mask(cell("plain"), pub, MaskingLevel.MASKED).value());
    }

    @Test
    void null_cell_and_null_value_preserved() {
        assertNull(engine.mask(null, policy(MaskingType.PHONE), MaskingLevel.MASKED));
        RowCell out = engine.mask(cell(null), policy(MaskingType.PHONE), MaskingLevel.MASKED);
        assertNull(out.value());
    }

    // ---------- 掩码算法 ----------

    @Test
    void phone_mask() {
        assertEquals("138****5678", engine.mask(cell("13812345678"), policy(MaskingType.PHONE), MaskingLevel.MASKED).value());
    }

    @Test
    void phone_short_value_masks_safely() {
        String out = engine.mask(cell("123"), policy(MaskingType.PHONE), MaskingLevel.MASKED).value();
        assertTrue(out.contains("*"));
        assertEquals(3, out.length());
    }

    @Test
    void id_card_mask() {
        assertEquals("110101********234X",
            engine.mask(cell("11010119900101234X"), policy(MaskingType.ID_CARD), MaskingLevel.MASKED).value());
    }

    @Test
    void bank_card_mask() {
        assertEquals("6222********7890",
            engine.mask(cell("6222021234567890"), policy(MaskingType.BANK_CARD), MaskingLevel.MASKED).value());
    }

    @Test
    void email_mask() {
        assertEquals("a****@example.com",
            engine.mask(cell("alice@example.com"), policy(MaskingType.EMAIL), MaskingLevel.MASKED).value());
    }

    @Test
    void email_single_char_local() {
        assertEquals("a*@x.com",
            engine.mask(cell("a@x.com"), policy(MaskingType.EMAIL), MaskingLevel.MASKED).value());
    }

    @Test
    void email_no_at_falls_back_safe() {
        String out = engine.mask(cell("nodomain"), policy(MaskingType.EMAIL), MaskingLevel.MASKED).value();
        assertTrue(out.contains("*"));
        assertEquals(8, out.length());
    }

    @Test
    void address_mask() {
        assertEquals("北京市朝阳区******",
            engine.mask(cell("北京市朝阳区建国路88号"), policy(MaskingType.ADDRESS), MaskingLevel.MASKED).value());
    }

    @Test
    void custom_mask() {
        MaskingConfig cfg = new MaskingConfig(2, 2, "#");
        ColumnMaskingPolicy p = new ColumnMaskingPolicy(1L, "c", SensitivityLevel.SENSITIVE, MaskingType.CUSTOM, cfg, "RULE");
        assertEquals("ab####xy", engine.mask(cell("abcdefxy"), p, MaskingLevel.MASKED).value());
    }

    @Test
    void masked_preserves_length() {
        String v = "13812345678";
        String out = engine.mask(cell(v), policy(MaskingType.PHONE), MaskingLevel.MASKED).value();
        assertEquals(v.length(), out.length());
    }

    @Test
    void masked_does_not_leak_original_when_masker_fails() {
        DefaultFieldMaskingEngine bad = new DefaultFieldMaskingEngine() {
            @Override
            String applyMasker(String value, MaskingType type, MaskingConfig config) {
                throw new RuntimeException("boom");
            }
        };
        String out = bad.mask(cell("13812345678"), policy(MaskingType.PHONE), MaskingLevel.MASKED).value();
        assertEquals("***********", out);
    }

    // ---------- 行级 ----------

    @Test
    void maskRow_aligns_policies_and_levels() {
        List<RowCell> cells = List.of(cell("13812345678"), cell("alice@example.com"), cell("plain"));
        List<ColumnMaskingPolicy> policies = Arrays.asList(policy(MaskingType.PHONE), policy(MaskingType.EMAIL), null);
        List<MaskingLevel> levels = List.of(MaskingLevel.MASKED, MaskingLevel.MASKED, MaskingLevel.MASKED);
        List<RowCell> out = engine.maskRow(cells, policies, levels);
        assertEquals(3, out.size());
        assertEquals("138****5678", out.get(0).value());
        assertEquals("a****@example.com", out.get(1).value());
        assertEquals("plain", out.get(2).value());
    }

    @Test
    void maskRow_handles_short_policies_safely() {
        List<RowCell> cells = List.of(cell("a"), cell("b"), cell("c"));
        List<RowCell> out = engine.maskRow(cells,
            List.of(policy(MaskingType.PHONE)),
            List.of(MaskingLevel.MASKED));
        assertEquals(3, out.size());
        assertEquals("*", out.get(0).value());
        assertNotNull(out.get(1));
        assertNotNull(out.get(2));
    }

    @Test
    void maskRow_empty_and_null_safe() {
        assertEquals(List.of(), engine.maskRow(null, null, null));
        assertEquals(List.of(), engine.maskRow(List.of(), null, null));
    }

    @Test
    void maskRow_mixed_levels_per_column() {
        List<RowCell> cells = List.of(cell("13812345678"), cell("alice@example.com"), cell("secret"));
        List<ColumnMaskingPolicy> policies = List.of(policy(MaskingType.PHONE), policy(MaskingType.EMAIL), policy(MaskingType.PHONE));
        List<MaskingLevel> levels = List.of(MaskingLevel.UNMASKED, MaskingLevel.MASKED, MaskingLevel.HIDDEN);
        List<RowCell> out = engine.maskRow(cells, policies, levels);
        assertEquals("13812345678", out.get(0).value());
        assertEquals("a****@example.com", out.get(1).value());
        assertNull(out.get(2).value());
    }

    @Test
    void sensitivity_level_ordering() {
        assertEquals(SensitivityLevel.RESTRICTED,
            SensitivityLevel.moreRestrictive(SensitivityLevel.SENSITIVE, SensitivityLevel.RESTRICTED));
        assertEquals(SensitivityLevel.SENSITIVE,
            SensitivityLevel.moreRestrictive(SensitivityLevel.SENSITIVE, SensitivityLevel.INTERNAL));
    }

    @Test
    void manual_classification_flag() {
        assertTrue(policy(MaskingType.PHONE).isManual());
        ColumnMaskingPolicy rule = new ColumnMaskingPolicy(1L, "c", SensitivityLevel.SENSITIVE, MaskingType.PHONE, null, "RULE");
        assertTrue(rule.isSensitive());
    }
}
