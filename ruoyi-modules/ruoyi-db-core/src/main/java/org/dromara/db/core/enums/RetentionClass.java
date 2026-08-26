package org.dromara.db.core.enums;

/**
 * 审计保留类别（docs/00 第 3.8 节）：查询/管理审计 1 年；权限/导出/变更审计 3 年
 *
 * @author DataGate
 */
public enum RetentionClass {

    ONE_YEAR,
    THREE_YEARS
}
