package org.dromara.db.auth.policy;

/**
 * 策略版本源端口（docs/03 第 8 节权限缓存）。
 *
 * <p>缓存键含 {@link #currentVersion()}：权限变更时版本递增并广播失效，旧决策不命中。
 * 默认实现读取授权表 max(policy_version)；后续切片可替换为 Valkey/版本表实现。</p>
 *
 * @author DataGate
 */
public interface PolicyVersionSource {

    /**
     * 当前策略版本。
     */
    long currentVersion();
}
