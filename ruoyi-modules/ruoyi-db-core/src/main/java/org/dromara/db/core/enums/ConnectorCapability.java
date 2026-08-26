package org.dromara.db.core.enums;

/**
 * 连接器能力声明（docs/02 第 7.1 节）。
 * 未声明的能力在 UI、API 和执行器三层均不可用。
 *
 * @author DataGate
 */
public enum ConnectorCapability {

    /**
     * 元数据目录同步
     */
    METADATA_CATALOG,

    /**
     * 只读查询
     */
    READ_QUERY,

    /**
     * 安全执行计划
     */
    EXPLAIN,

    /**
     * 受控导出
     */
    EXPORT,

    /**
     * DML 变更
     */
    CHANGE_DML,

    /**
     * DDL 变更
     */
    CHANGE_DDL,

    /**
     * 慢查询采集
     */
    SLOW_QUERY_PULL,

    /**
     * 查询取消
     */
    QUERY_CANCEL,

    /**
     * 列来源追踪
     */
    COLUMN_LINEAGE,

    /**
     * 字段级脱敏
     */
    FIELD_MASKING
}
