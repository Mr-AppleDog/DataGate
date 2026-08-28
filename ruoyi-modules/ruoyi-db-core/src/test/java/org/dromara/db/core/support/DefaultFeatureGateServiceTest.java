package org.dromara.db.core.support;

import org.dromara.db.core.enums.FeatureGate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 功能开关默认实现单元测试（docs/09 §14.3，M6-01a）。
 *
 * @author DataGate
 */
@Tag("unit")
class DefaultFeatureGateServiceTest {

    @Test
    void default_enabled_when_not_configured() {
        DefaultFeatureGateService svc = new DefaultFeatureGateService(Map.of(), Map.of());
        assertTrue(svc.isEnabled(FeatureGate.EXPORT));
        assertTrue(svc.isEnabled(FeatureGate.CHANGE_DDL, 1L));
    }

    @Test
    void global_default_can_disable() {
        DefaultFeatureGateService svc = new DefaultFeatureGateService(Map.of(FeatureGate.EXPORT, false), Map.of());
        assertFalse(svc.isEnabled(FeatureGate.EXPORT));
        assertFalse(svc.isEnabled(FeatureGate.EXPORT, 99L)); // 无覆盖→用全局默认
    }

    @Test
    void per_datasource_override() {
        DefaultFeatureGateService svc = new DefaultFeatureGateService(
            Map.of(FeatureGate.EXPORT, false), // 全局关闭
            Map.of("5:" + FeatureGate.EXPORT.name(), true)); // 数据源5覆盖开启
        assertFalse(svc.isEnabled(FeatureGate.EXPORT, 1L)); // 其他数据源仍关闭
        assertTrue(svc.isEnabled(FeatureGate.EXPORT, 5L)); // 数据源5灰度开启
        assertFalse(svc.isEnabled(FeatureGate.EXPORT)); // 环境默认关闭
    }

    @Test
    void null_feature_safe() {
        DefaultFeatureGateService svc = new DefaultFeatureGateService(Map.of(), Map.of());
        assertTrue(svc.isEnabled(null));
    }

    @Test
    void security_rules_not_in_gate_cannot_be_disabled() {
        // 安全规则（脱敏/只读/审批/审计失败关闭）不在 FeatureGate 枚举，无开关可关
        // 这里验证 FeatureGate 枚举不包含安全语义项
        for (FeatureGate f : FeatureGate.values()) {
            String n = f.name();
            assertFalse(n.contains("MASK") || n.contains("AUDIT") || n.contains("READONLY"),
                "安全规则不得作为功能开关：" + n);
        }
    }
}
