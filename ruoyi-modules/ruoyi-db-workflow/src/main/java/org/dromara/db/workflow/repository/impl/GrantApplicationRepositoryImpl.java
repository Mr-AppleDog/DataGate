package org.dromara.db.workflow.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.mybatis.core.page.PageQuery;
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

    @Override
    public boolean updateFlowInstanceId(Long id, Long flowInstanceId) {
        return applicationMapper.update(null, new LambdaUpdateWrapper<GrantApplication>()
            .eq(GrantApplication::getId, id)
            .set(GrantApplication::getFlowInstanceId, flowInstanceId)
            .set(GrantApplication::getUpdateTime, java.time.Instant.now())) > 0;
    }

    @Override
    public boolean updateStatus(Long id, String status) {
        return applicationMapper.update(null, new LambdaUpdateWrapper<GrantApplication>()
            .eq(GrantApplication::getId, id)
            .set(GrantApplication::getStatus, status)
            .set(GrantApplication::getUpdateTime, java.time.Instant.now())) > 0;
    }

    @Override
    public Page<GrantApplication> page(Long applicantId, PageQuery pageQuery) {
        LambdaQueryWrapper<GrantApplication> wrapper = Wrappers.lambdaQuery();
        if (applicantId != null) {
            wrapper.eq(GrantApplication::getApplicantId, applicantId);
        }
        wrapper.eq(GrantApplication::getDelFlag, "0")
            .orderByDesc(GrantApplication::getCreateTime);
        return applicationMapper.selectPage(pageQuery.build(), wrapper);
    }
}
