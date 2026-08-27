package org.dromara.db.auth.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.auth.repository.GrantRepository;

/**
 * 默认策略版本源：取授权表 max(policy_version)（docs/03 第 8 节）。
 *
 * <p>由 {@code PolicyDefaultsConfiguration} 以 {@code @Bean @ConditionalOnMissingBean} 注册
 * （@ConditionalOnMissingBean 用在 @Component 上不可靠，故移到 @Configuration @Bean）。
 * Valkey/版本表实现后续以同条件覆盖。</p>
 *
 * @author DataGate
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultPolicyVersionSource implements PolicyVersionSource {

    private final GrantRepository grantRepository;

    @Override
    public long currentVersion() {
        return grantRepository.maxPolicyVersion();
    }
}
