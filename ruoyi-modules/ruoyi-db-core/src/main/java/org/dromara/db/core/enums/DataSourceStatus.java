package org.dromara.db.core.enums;

/**
 * 数据源状态机（docs/05 第 4.1 节）。
 * DRAFT → VERIFYING → ACTIVE；只有验证成功才能 ACTIVE；ARCHIVED 不可恢复。
 *
 * @author DataGate
 */
public enum DataSourceStatus {

    DRAFT,
    VERIFYING,
    ACTIVE,
    DISABLED,
    ERROR,
    ARCHIVED
}
