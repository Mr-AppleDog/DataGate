package org.dromara.system.support;

import lombok.RequiredArgsConstructor;
import org.dromara.db.auth.resolver.SubjectMembership;
import org.dromara.db.auth.resolver.SubjectMembershipResolver;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将 RuoYi 用户、部门和启用角色解析为 DataGate 授权主体快照。
 *
 * <p>这里只提供主体归属，不直接放行任何数据库动作。角色必须在
 * {@code dbg_resource_grant} 中存在显式 ROLE 授权后才会生效。</p>
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class RuoYiSubjectMembershipResolver implements SubjectMembershipResolver {

    private static final String STATUS_NORMAL = "0";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public SubjectMembership resolve(Long actorId) {
        if (actorId == null) {
            return SubjectMembership.empty(null);
        }
        SysUser user = userMapper.selectById(actorId);
        if (user == null || !STATUS_NORMAL.equals(user.getStatus())) {
            return SubjectMembership.empty(actorId);
        }

        Set<Long> deptIds = user.getDeptId() == null ? Set.of() : Set.of(user.getDeptId());
        Set<Long> roleIds = roleMapper.selectRolesByUserId(actorId).stream()
            .filter(role -> STATUS_NORMAL.equals(role.getStatus()))
            .map(SysRoleVo::getRoleId)
            .collect(Collectors.toUnmodifiableSet());
        return new SubjectMembership(actorId, deptIds, Set.of(), roleIds);
    }
}
