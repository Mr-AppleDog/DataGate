package org.dromara.db.auth.repository.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.mapper.GrantMapper;
import org.dromara.db.auth.repository.GrantWriteRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * 授权写入 MyBatis 实现。选择性更新避免覆盖业务字段。
 *
 * @author DataGate
 */
@Repository
@RequiredArgsConstructor
public class GrantWriteRepositoryImpl implements GrantWriteRepository {

    private final GrantMapper grantMapper;

    @Override
    public Long insert(Grant grant) {
        grantMapper.insert(grant);
        return grant.getId();
    }

    @Override
    public Grant findById(Long id) {
        return grantMapper.selectById(id);
    }

    @Override
    public boolean updateRevoked(Long id, Instant revokedAt, Long policyVersion, Long updateBy) {
        return grantMapper.update(null, new LambdaUpdateWrapper<Grant>()
            .eq(Grant::getId, id)
            .set(Grant::getRevokedAt, revokedAt)
            .set(Grant::getPolicyVersion, policyVersion)
            .set(Grant::getUpdateBy, updateBy)) > 0;
    }
}
