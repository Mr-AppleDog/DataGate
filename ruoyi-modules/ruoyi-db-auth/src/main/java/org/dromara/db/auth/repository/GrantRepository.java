package org.dromara.db.auth.repository;

import org.dromara.db.auth.domain.Grant;
import org.dromara.db.core.enums.DbAction;

import java.util.List;
import java.util.Set;

/**
 * 授权候选查询端口（docs/03 第 7.2 节 step 4，AUTH-001~004）。
 *
 * <p>只做结构匹配：resource_id ∈ (资源+祖先) 或 GLOBAL、action 匹配、
 * 主体匹配（user/dept/group/role）、未逻辑删除。
 * 生效/过期/撤销/条件判定在鉴权服务内完成，便于产出细粒度原因码与可测试性。</p>
 *
 * <p>实现：{@link GrantRepositoryImpl}（MyBatis-Plus）；测试可用内存实现替换。</p>
 *
 * @author DataGate
 */
public interface GrantRepository {

    /**
     * 加载候选授权（结构匹配，未做时效过滤）。
     *
     * @param resourceIds 资源及祖先资源 ID（含自身；为空返回空列表）
     * @param action     资源动作
     * @param actorId    操作人 ID（USER 主体匹配；可为 null 表示仅匹配 dept/group）
     * @param deptIds    用户所属部门 ID（DEPT 主体匹配；空集表示无）
     * @param groupIds   用户所属用户组 ID（GROUP 主体匹配；空集表示无）
     * @return 候选授权列表（未排序保证）
     */
    List<Grant> findCandidates(List<Long> resourceIds, DbAction action,
                                Long actorId, Set<Long> deptIds, Set<Long> groupIds);

    /**
     * 加载包含角色主体的候选授权。默认实现兼容尚未提供角色解析的测试/适配实现。
     *
     * @param roleIds 用户所属启用角色 ID（ROLE 主体匹配；空集表示无）
     */
    default List<Grant> findCandidates(List<Long> resourceIds, DbAction action,
                                       Long actorId, Set<Long> deptIds, Set<Long> groupIds,
                                       Set<Long> roleIds) {
        return findCandidates(resourceIds, action, actorId, deptIds, groupIds);
    }

    /**
     * 当前策略版本（max(policy_version)，docs/03 第 8 节缓存键）。
     */
    long maxPolicyVersion();
}
