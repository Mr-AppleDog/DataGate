package org.dromara.db.workflow.repository.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.db.workflow.domain.GrantApplication;
import org.dromara.db.workflow.mapper.GrantApplicationMapper;
import org.dromara.db.workflow.repository.GrantApplicationRepository;
import org.springframework.stereotype.Repository;

/**
 * 申请单 MyBatis 实现。
 *
 * @author DataGate
 */
@Repository
@RequiredArgsConstructor
public class GrantApplicationRepositoryImpl implements GrantApplicationRepository {

    private final GrantApplicationMapper applicationMapper;

    @Override
    public GrantApplication findById(Long id) {
        return applicationMapper.selectById(id);
    }

    @Override
    public Long insert(GrantApplication application) {
        applicationMapper.insert(application);
        return application.getId();
    }

    @Override
    public boolean updateApprovalResult(Long id, String status, Long grantId, Long approverId) {
        return applicationMapper.update(null, new LambdaUpdateWrapper<GrantApplication>()
            .eq(GrantApplication::getId, id)
            .set(GrantApplication::getStatus, status)
            .set(GrantApplication::getGrantId, grantId)
            .set(GrantApplication::getApproverId, approverId)
            .set(GrantApplication::getUpdateTime, java.time.Instant.now())) > 0;
    }
}
