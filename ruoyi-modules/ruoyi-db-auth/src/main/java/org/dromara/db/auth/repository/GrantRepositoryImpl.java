package org.dromara.db.auth.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.mapper.GrantMapper;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.SubjectType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * 基于 MyBatis-Plus 的授权候选查询实现（AUTH-001~004）。
 *
 * <p>主体匹配以 OR 分组组装：(subject_type=USER AND subject_id=actorId) ∨
 * (subject_type=DEPT AND subject_id∈deptIds) ∨ (subject_type=GROUP AND subject_id∈groupIds)。
 * 无任何主体可匹配时直接返回空列表，避免生成空 OR 子句。</p>
 *
 * @author DataGate
 */
@RequiredArgsConstructor
@Repository
public class GrantRepositoryImpl implements GrantRepository {

    private final GrantMapper grantMapper;

    @Override
    public List<Grant> findCandidates(List<Long> resourceIds, DbAction action,
                                      Long actorId, Set<Long> deptIds, Set<Long> groupIds) {
        if (resourceIds == null || resourceIds.isEmpty() || action == null) {
            return List.of();
        }
        boolean hasUser = actorId != null;
        boolean hasDept = deptIds != null && !deptIds.isEmpty();
        boolean hasGroup = groupIds != null && !groupIds.isEmpty();
        if (!hasUser && !hasDept && !hasGroup) {
            return List.of();
        }

        LambdaQueryWrapper<Grant> w = Wrappers.lambdaQuery(Grant.class)
            .in(Grant::getResourceId, resourceIds)
            .eq(Grant::getAction, action)
            .eq(Grant::getDelFlag, "0")
            .and(subject -> {
                if (hasUser) {
                    subject.or(s -> s.eq(Grant::getSubjectType, SubjectType.USER)
                        .eq(Grant::getSubjectId, actorId));
                }
                if (hasDept) {
                    for (Long d : deptIds) {
                        subject.or(s -> s.eq(Grant::getSubjectType, SubjectType.DEPT)
                            .eq(Grant::getSubjectId, d));
                    }
                }
                if (hasGroup) {
                    for (Long g : groupIds) {
                        subject.or(s -> s.eq(Grant::getSubjectType, SubjectType.GROUP)
                            .eq(Grant::getSubjectId, g));
                    }
                }
            });
        return grantMapper.selectList(w);
    }

    @Override
    public long maxPolicyVersion() {
        return grantMapper.maxPolicyVersion();
    }
}
