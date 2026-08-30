package org.dromara.db.auth.resolver;

import java.util.Set;

/**
 * 用户主体归属快照（{@link SubjectMembershipResolver} 返回值，docs/03 第 5.2 节）。
 *
 * @param actorId  用户 ID
 * @param deptIds  所属部门 ID 集合
 * @param groupIds 所属用户组 ID 集合
 * @param roleIds  所属启用角色 ID 集合
 * @author DataGate
 */
public record SubjectMembership(Long actorId, Set<Long> deptIds, Set<Long> groupIds, Set<Long> roleIds) {

    public SubjectMembership {
        deptIds = deptIds == null ? Set.of() : Set.copyOf(deptIds);
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
        roleIds = roleIds == null ? Set.of() : Set.copyOf(roleIds);
    }

    /**
     * 兼容仅解析部门和用户组的既有实现。
     */
    public SubjectMembership(Long actorId, Set<Long> deptIds, Set<Long> groupIds) {
        this(actorId, deptIds, groupIds, Set.of());
    }

    /**
     * 空归属（无实现注入或用户无部门/组）
     */
    public static SubjectMembership empty(Long actorId) {
        return new SubjectMembership(actorId, Set.of(), Set.of(), Set.of());
    }
}
