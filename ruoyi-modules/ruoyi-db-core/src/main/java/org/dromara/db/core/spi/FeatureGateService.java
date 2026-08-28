package org.dromara.db.core.spi;

import org.dromara.db.core.enums.FeatureGate;

/**
 * 功能开关服务（docs/09 §14.3，M6-01a）。
 *
 * <p>按环境/数据源判断某功能是否启用，供连接器注册表/执行网关/工单服务灰度决策。
 * 安全规则不由此服务管控（不得永久关闭脱敏/只读/审批/审计失败关闭）。</p>
 *
 * @author DataGate
 */
public interface FeatureGateService {

    /**
     * 功能是否启用（可按数据源覆盖）。
     *
     * @param feature 功能项
     * @param dataSourceId 数据源 ID（可空：取环境默认）
     * @return true 启用
     */
    boolean isEnabled(FeatureGate feature, Long dataSourceId);

    /** 不带数据源（环境默认） */
    default boolean isEnabled(FeatureGate feature) {
        return isEnabled(feature, null);
    }
}
