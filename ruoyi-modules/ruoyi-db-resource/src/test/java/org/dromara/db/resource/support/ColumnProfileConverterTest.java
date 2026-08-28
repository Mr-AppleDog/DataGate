package org.dromara.db.resource.support;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.MaskingConfig;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.dromara.db.resource.domain.DbColumnProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列策略转换器单元测试（docs/04 §3.7、docs/10 单元类别"脱敏"）。
 *
 * @author DataGate
 */
@Tag("unit")
class ColumnProfileConverterTest {

    private DbColumnProfile row(Long id, String level, String type, String source) {
        DbColumnProfile p = new DbColumnProfile();
        p.setResourceId(id);
        p.setSensitivityLevel(level);
        p.setMaskingType(type);
        p.setClassificationSource(source);
        return p;
    }

    @Test
    void to_policy_maps_fields() {
        DbColumnProfile p = row(1001L, "SENSITIVE", "PHONE", "MANUAL");
        ColumnMaskingPolicy policy = ColumnProfileConverter.toPolicy(p);
        assertEquals(1001L, policy.resourceId());
        assertEquals(SensitivityLevel.SENSITIVE, policy.sensitivityLevel());
        assertEquals(MaskingType.PHONE, policy.maskingType());
        assertEquals("MANUAL", policy.classificationSource());
        assertTrue(policy.isManual());
        assertTrue(policy.isSensitive());
    }

    @Test
    void to_policy_null_safe() {
        assertNull(ColumnProfileConverter.toPolicy(null));
    }

    @Test
    void to_policy_invalid_enum_falls_back_safe() {
        DbColumnProfile p = row(1L, "WHOOPS", "NOPE", "RULE");
        ColumnMaskingPolicy policy = ColumnProfileConverter.toPolicy(p);
        // 非法枚举回退到 PUBLIC/NONE（非敏感透传），不抛异常
        assertEquals(SensitivityLevel.PUBLIC, policy.sensitivityLevel());
        assertEquals(MaskingType.NONE, policy.maskingType());
        assertFalse(policy.isSensitive());
    }

    @Test
    void public_level_is_not_sensitive() {
        DbColumnProfile p = row(1L, "PUBLIC", "PHONE", "RULE");
        assertFalse(ColumnProfileConverter.toPolicy(p).isSensitive());
    }

    @Test
    void parse_config_valid() {
        MaskingConfig cfg = ColumnProfileConverter.parseConfig("{\"keepPrefix\":2,\"keepSuffix\":2,\"maskChar\":\"#\"}");
        assertNotNull(cfg);
        assertEquals(2, cfg.keepPrefix());
        assertEquals(2, cfg.keepSuffix());
        assertEquals("#", cfg.maskChar());
    }

    @Test
    void parse_config_null_and_blank_safe() {
        assertNull(ColumnProfileConverter.parseConfig(null));
        assertNull(ColumnProfileConverter.parseConfig(""));
        assertNull(ColumnProfileConverter.parseConfig("   "));
    }

    @Test
    void parse_config_malformed_returns_null() {
        assertNull(ColumnProfileConverter.parseConfig("{not valid json"));
    }

    @Test
    void parse_config_defaults_missing_fields() {
        MaskingConfig cfg = ColumnProfileConverter.parseConfig("{}");
        assertNotNull(cfg);
        assertEquals(0, cfg.keepPrefix());
        assertEquals(0, cfg.keepSuffix());
        assertEquals("*", cfg.maskChar());
    }

    @Test
    void manual_label_is_preserved_on_resync() {
        DbColumnProfile manual = row(1L, "SENSITIVE", "PHONE", "MANUAL");
        assertTrue(ColumnProfileConverter.shouldPreserveManual(manual));
    }

    @Test
    void rule_and_import_labels_are_overwritable() {
        assertFalse(ColumnProfileConverter.shouldPreserveManual(row(1L, "INTERNAL", "NONE", "RULE")));
        assertFalse(ColumnProfileConverter.shouldPreserveManual(row(1L, "SENSITIVE", "EMAIL", "IMPORT")));
    }

    @Test
    void null_existing_is_overwritable() {
        assertFalse(ColumnProfileConverter.shouldPreserveManual(null));
    }
}
