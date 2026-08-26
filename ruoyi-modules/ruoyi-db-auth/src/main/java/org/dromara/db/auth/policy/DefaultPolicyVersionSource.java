package org.dromara.db.auth.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.auth.repository.GrantRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 默认策略版本源：取授权表 max(policy_version)（docs/03 第 8 节）。
 *
 * <p>当后续切片提供 Valkey/独立版本表实现时，以 {@link ConditionalOnMissingBean} 让位。</p>
 *
 * @author DataGate
 */
@Slf4j
@RequiredArgsConstructor
@Component
@ConditionalOnMissingBean(PolicyVersionSource.class)
public class DefaultPolicyVersionSource implements PolicyVersionSource {

    private final GrantRepository grantRepository;

    @Override
    public long currentVersion() {
        return grantRepository.maxPolicyVersion();
    }
}
