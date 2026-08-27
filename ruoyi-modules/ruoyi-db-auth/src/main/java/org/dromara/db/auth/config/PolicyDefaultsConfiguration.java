package org.dromara.db.auth.config;

import org.dromara.db.auth.policy.DefaultPolicyVersionSource;
import org.dromara.db.auth.policy.NoOpPolicyCacheInvalidationHook;
import org.dromara.db.auth.policy.PolicyCacheInvalidationHook;
import org.dromara.db.auth.policy.PolicyVersionSource;
import org.dromara.db.auth.repository.GrantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 策略版本与缓存失效的默认实现装配（docs/03 第 8 节）。
 *
 * <p>以 {@code @Bean @ConditionalOnMissingBean} 在 @Configuration 中注册——
 * 该条件在 @Configuration 处理期可靠求值（@Component 上的同条件不可靠，
 * 曾导致 PolicyVersionSource 无 bean、应用启动失败）。
 * Valkey/独立版本表实现后续以同条件覆盖这两个默认 bean。</p>
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

    @Bean
    @ConditionalOnMissingBean(PolicyCacheInvalidationHook.class)
    public PolicyCacheInvalidationHook noOpPolicyCacheInvalidationHook() {
        return new NoOpPolicyCacheInvalidationHook();
    }
}
