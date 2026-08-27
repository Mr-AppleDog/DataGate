package org.dromara.db.auth.service;

import org.dromara.db.auth.domain.Grant;

/**
 * 授权管理服务（写侧，docs/03 §2.2、docs/04 §4.2，AUTH-001~004）。
 *
 * <p>授权创建与撤销由审批流（db-workflow，M2-02）调用：
 * 批准生成 Grant（policy_version 递增 + 缓存失效广播）；撤回/到期/撤销写 revoked_at + 版本递增 + 广播，
 * 使撤权 60s 内全局生效（docs/03 §8）。</p>
 *
 * @author DataGate
 */
public interface GrantAdminService {

    /**
     * 创建授权。调用方填业务字段（subject/resource/action/effect/conditions/effective/expires/source）；
     * 本服务填 policyVersion（当前版本+1）+ 审计字段，持久化并广播失效。
     *
     * @param grant  授权（业务字段已填）
     * @param actorId 操作人（审批人/管理员，写 createBy/updateBy）
     * @return 授权 ID
     */
    Long createGrant(Grant grant, Long actorId);

    /**
     * 撤销授权（幂等：已撤销不重复处理）。
     *
     * @param grantId 授权 ID
     * @param actorId 操作人
     * @return 是否实际撤销（false 表示本就未授权/已撤销）
     */
    boolean revokeGrant(Long grantId, Long actorId);
}
