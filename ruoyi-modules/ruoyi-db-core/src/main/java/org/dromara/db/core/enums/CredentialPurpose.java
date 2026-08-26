package org.dromara.db.core.enums;

/**
 * 凭据用途（docs/00 第 3.6 节）。禁止同一数据库账号同时用于查询/变更/采集。
 *
 * @author DataGate
 */
public enum CredentialPurpose {

    /**
     * 只读查询账号
     */
    QUERY,

    /**
     * 变更工单专用账号
     */
    CHANGE,

    /**
     * 慢查询/监控采集账号
     */
    MONITOR
}
