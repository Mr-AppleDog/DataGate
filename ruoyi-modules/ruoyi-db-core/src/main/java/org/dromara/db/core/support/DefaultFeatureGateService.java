package org.dromara.db.core.support;

import org.dromara.db.core.enums.FeatureGate;
import org.dromara.db.core.spi.FeatureGateService;

import java.util.Map;

/**
 * 默认功能开关实现（docs/09 §14.3，M6-01a）。
 *
 * <p>纯逻辑：全局默认（feature→enabled，缺省 true 即灰度默认开放）+ 数据源级覆盖
 * （"dataSourceId:feature"→enabled）。安全规则不在 {@link FeatureGate} 枚举，无法经此关闭。
 * 由 Spring 配置（读 application.yml datagate.feature.* + Valkey 热更新）注入 defaults/overrides。</p>
 *
 * @author DataGate
 */
public class DefaultFeatureGateService implements FeatureGateService {

    private final Map<FeatureGate, Boolean> defaults;
    private final Map<String, Boolean> overrides;

    public DefaultFeatureGateService(Map<FeatureGate, Boolean> defaults, Map<String, Boolean> overrides) {
        this.defaults = defaults == null ? Map.of() : Map.copyOf(defaults);
        this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    @Override
    public boolean isEnabled(FeatureGate feature, Long dataSourceId) {
        if (feature == null) {
            return true; // 未知功能默认放行（保守：不阻断已有能力）
        }
        if (dataSourceId != null) {
            Boolean overridden = overrides.get(dataSourceId + ":" + feature.name());
            if (overridden != null) {
                return overridden;
            }
        }
        return defaults.getOrDefault(feature, Boolean.TRUE);
    }
}
