package org.dromara.db.auth;

import org.dromara.db.auth.resolver.SubjectMembership;
import org.dromara.db.auth.resolver.SubjectMembershipResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 用户主体归属解析桩（测试用）：按 actorId 返回预设的部门/用户组归属。
 *
 * @author DataGate
 */
public class StubMembershipResolver implements SubjectMembershipResolver {

    private final Map<Long, SubjectMembership> memberships = new HashMap<>();

    public StubMembershipResolver put(Long actorId, Set<Long> deptIds, Set<Long> groupIds) {
        memberships.put(actorId, new SubjectMembership(actorId, deptIds, groupIds));
        return this;
    }

    @Override
    public SubjectMembership resolve(Long actorId) {
        return memberships.getOrDefault(actorId, SubjectMembership.empty(actorId));
    }
}
