package org.dromara.db.resource.config;

import org.dromara.db.core.enums.FeatureGate;
import org.dromara.db.core.spi.FeatureGateService;
import org.dromara.db.core.support.DefaultFeatureGateService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import java.util.EnumMap;
import java.util.Map;

/**
 * 功能开关装配（docs/09 §14.3，M6-01a）。
 *
 * <p>读 application.yml datagate.feature.defaults / overrides 构造 DefaultFeatureGateService。
 * 安全规则不在此装配（不得永久关闭脱敏/只读/审批/审计失败关闭）。</p>
 *
 * @author DataGate
 */
@Configuration
@EnableConfigurationProperties(FeatureGateConfiguration.Properties.class)
public class FeatureGateConfiguration {

    @ConfigurationProperties(prefix = "datagate.feature")
    public record Properties(Map<String, Boolean> defaults, Map<String, Boolean> overrides) {
    }

    @Bean
    @ConditionalOnMissingBean(FeatureGateService.class)
    public FeatureGateService featureGateService(Properties props) {
        Map<FeatureGate, Boolean> defaults = new EnumMap<>(FeatureGate.class);
        if (props != null && props.defaults() != null) {
            props.defaults().forEach((k, v) -> {
                try {
                    defaults.put(FeatureGate.valueOf(k.toUpperCase().replace('-', '_')), v);
                } catch (IllegalArgumentException ignored) {
                    // 未知开关项忽略
                }
            });
        }
        Map<String, Boolean> overrides = (props != null && props.overrides() != null) ? props.overrides() : Map.of();
        return new DefaultFeatureGateService(defaults, overrides);
    }
}
