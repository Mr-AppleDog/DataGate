package org.dromara.db.auth.resolver;

/**
 * 用户主体归属解析端口（docs/03 第 5.2 节主体继承，IAM）。
 *
 * <p>实现由 ruoyi-system 提供（用户所属部门、用户组）；db-auth 不直接依赖 ruoyi-system 模块，
 * 通过 Spring 可选注入接入。用于在候选授权上匹配 DEPT/GROUP/ROLE 主体。
 * 无实现注入时返回 {@link SubjectMembership#empty}（仅 USER 直接授权生效，已在报告中标注）。</p>
 *
 * @author DataGate
 */
public interface SubjectMembershipResolver {

    /**
     * 解析用户的部门、用户组与启用角色归属。
     *
     * @param actorId 用户 ID
     * @return 主体归属快照（deptIds/groupIds/roleIds 可空集合）
     */
    SubjectMembership resolve(Long actorId);
}
