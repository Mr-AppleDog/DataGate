package org.dromara.db.auth.policy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 默认缓存失效钩子：空实现（docs/03 第 8 节）。
 *
 * <p>本切片不硬依赖 Valkey；Valkey 装配在后续切片以 {@link ConditionalOnMissingBean} 覆盖。</p>
 *
 * @author DataGate
 */
@Slf4j
@Component
@ConditionalOnMissingBean(PolicyCacheInvalidationHook.class)
public class NoOpPolicyCacheInvalidationHook implements PolicyCacheInvalidationHook {

    @Override
    public void onPolicyChanged(long newVersion) {
        log.debug("policy version bumped to {} (no-op cache hook; Valkey wiring pending)", newVersion);
    }

    @Override
    public void onUserInvalidated(Long actorId) {
        log.debug("user {} invalidated (no-op cache hook; Valkey wiring pending)", actorId);
    }
}
