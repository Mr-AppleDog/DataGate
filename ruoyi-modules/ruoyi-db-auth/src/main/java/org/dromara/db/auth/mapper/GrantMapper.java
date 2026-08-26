package org.dromara.db.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.auth.domain.Grant;

/**
 * 授权 Mapper（docs/04 第 4.2 节，AUTH-001~004）。
 *
 * <p>候选授权查询以 MyBatis-Plus {@code LambdaQueryWrapper} 在 {@code GrantRepository} 中组装
 * （resource_id IN (资源+祖先)、action 匹配、主体匹配、del_flag=0），经 {@code selectList} 执行，
 * 避免手写动态 SQL 的注入风险。生效/过期/撤销的过滤在服务层完成，以便区分 DEFAULT_DENY 等
 * 细粒度原因码（见 {@code AuthorizationDecisionServiceImpl}）。</p>
 *
 * <p>只读：本切片不提供授权的 insert/update/delete 业务入口，授权生命周期由审批流负责。</p>
 *
 * @author DataGate
 */
@Mapper
public interface GrantMapper extends BaseMapperPlus<Grant, Grant> {

    /**
     * 当前策略版本：取未删除授权的最大 policy_version（docs/03 第 8 节）。
     * 无授权时返回 0。
     */
    @Select("SELECT COALESCE(max(policy_version), 0) FROM dbg_resource_grant WHERE del_flag = '0'")
    long maxPolicyVersion();
}
