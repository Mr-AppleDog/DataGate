package org.dromara.db.auth.config;

import org.dromara.db.auth.policy.DefaultPolicyVersionSource;
import org.dromara.db.auth.policy.PolicyVersionSource;
import org.dromara.db.auth.repository.GrantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 策略版本源的默认装配（docs/03 第 8 节）。
 *
 * <p>以 {@code @Bean @ConditionalOnMissingBean} 在 @Configuration 中注册——
 * 该条件在 @Configuration 处理期可靠求值（@Component 上的同条件不可靠，
 * 曾导致 PolicyVersionSource 无 bean、应用启动失败）。
 * Valkey/独立版本表实现后续以同条件覆盖。</p>
 *
 * <p>权限缓存失效钩子 {@link org.dromara.db.auth.policy.PolicyCacheInvalidationHook} 由
 * {@link DecisionCache}（{@link AuthorizationConfig} 注册，无条件 bean）承担——它同时是决策缓存
 * 与失效钩子，撤权/用户失效即时清本节点缓存。{@code NoOpPolicyCacheInvalidationHook} 仅保留为
 * 备用类，不再注册（避免与 DecisionCache 产生同类型双 bean 歧义）。</p>
 *
 * @author DataGate
 */
@Configuration
public class PolicyDefaultsConfiguration {

    @Bean
    @ConditionalOnMissingBean(PolicyVersionSource.class)
    public PolicyVersionSource defaultPolicyVersionSource(GrantRepository grantRepository) {
        return new DefaultPolicyVersionSource(grantRepository);
    }
}
