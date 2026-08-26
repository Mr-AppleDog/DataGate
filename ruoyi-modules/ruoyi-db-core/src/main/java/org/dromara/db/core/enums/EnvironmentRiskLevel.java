package org.dromara.db.core.enums;

/**
 * 环境风险等级（docs/04 第 3.1 节）。
 * 生产环境必须为 CRITICAL，硬安全上限不可通过普通配置降低。
 *
 * @author DataGate
 */
public enum EnvironmentRiskLevel {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
