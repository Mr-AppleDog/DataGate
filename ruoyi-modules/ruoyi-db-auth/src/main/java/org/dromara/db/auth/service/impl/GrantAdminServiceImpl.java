package org.dromara.db.auth.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.policy.PolicyCacheInvalidationHook;
import org.dromara.db.auth.policy.PolicyVersionSource;
import org.dromara.db.auth.repository.GrantWriteRepository;
import org.dromara.db.auth.service.GrantAdminService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 授权管理实现（写侧，M2-02 前置）。
 *
 * <p>policy_version 取当前版本+1 并写入；同时广播失效令旧决策缓存不命中（撤权 60s 生效，docs/03 §8）。
 * 缓存广播失败降级到 TTL（5min）自然过期，不阻断授权写入。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantAdminServiceImpl implements GrantAdminService {

    private final GrantWriteRepository writeRepository;
    private final PolicyVersionSource policyVersionSource;
    private final PolicyCacheInvalidationHook invalidationHook;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createGrant(Grant grant, Long actorId) {
        long newVersion = policyVersionSource.currentVersion() + 1;
        grant.setPolicyVersion(newVersion);
        Instant now = Instant.now();
        if (grant.getCreateTime() == null) {
            grant.setCreateTime(now);
        }
        grant.setUpdateTime(now);
        grant.setCreateBy(actorId);
        grant.setUpdateBy(actorId);
        if (grant.getRevokedAt() != null) {
            throw new IllegalArgumentException("不能创建已撤销的授权");
        }
        Long id = writeRepository.insert(grant);
        invalidate(newVersion);
        return id;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean revokeGrant(Long grantId, Long actorId) {
        Grant grant = writeRepository.findById(grantId);
        if (grant == null) {
            return false;
        }
        if (grant.getRevokedAt() != null) {
            // 幂等：已撤销不再重复递增版本/广播
            return false;
        }
        long newVersion = policyVersionSource.currentVersion() + 1;
        writeRepository.updateRevoked(grantId, Instant.now(), newVersion, actorId);
        invalidate(newVersion);
        return true;
    }

    private void invalidate(long newVersion) {
        try {
            invalidationHook.onPolicyChanged(newVersion);
        } catch (Exception e) {
            log.warn("权限缓存失效广播失败，降级到 TTL 自然过期: version={}", newVersion, e);
        }
    }
}
