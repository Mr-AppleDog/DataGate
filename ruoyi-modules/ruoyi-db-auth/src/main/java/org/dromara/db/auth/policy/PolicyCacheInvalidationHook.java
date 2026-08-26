package org.dromara.db.auth.policy;

/**
 * 权限缓存失效钩子端口（docs/03 第 8 节）。
 *
 * <p>权限变更、用户禁用/退出/临时回收触发调用；实现负责删除会话级缓存并广播跨节点失效。
 * 本切片定义接口位与默认空实现；Valkey 装配在后续切片。</p>
 *
 * @author DataGate
 */
public interface PolicyCacheInvalidationHook {

    /**
     * 策略版本变更（撤权/新增授权）触发，令旧缓存不命中。
     *
     * @param newVersion 新策略版本
     */
    void onPolicyChanged(long newVersion);

    /**
     * 用户级失效（禁用/退出/离职/部门变动），删除该用户会话级缓存（docs/03 第 8 节）。
     *
     * @param actorId 用户 ID
     */
    void onUserInvalidated(Long actorId);
}
