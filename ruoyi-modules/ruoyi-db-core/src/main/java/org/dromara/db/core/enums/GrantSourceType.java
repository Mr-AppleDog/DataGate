package org.dromara.db.core.enums;

/**
 * 授权来源（docs/03 第 2.2 节）
 *
 * @author DataGate
 */
public enum GrantSourceType {

    /**
     * 人工直接授权
     */
    MANUAL,

    /**
     * 审批工单生成
     */
    REQUEST,

    /**
     * 系统策略
     */
    SYSTEM,

    /**
     * 紧急授权
     */
    EMERGENCY
}
