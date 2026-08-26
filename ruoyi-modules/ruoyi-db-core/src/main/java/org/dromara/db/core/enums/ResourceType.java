package org.dromara.db.core.enums;

/**
 * 资源类型（docs/03 第 3 节资源模型）
 *
 * @author DataGate
 */
public enum ResourceType {

    /**
     * 数据源实例（资源树根）
     */
    DATA_SOURCE,

    /**
     * 数据库（MySQL database / PostgreSQL database）
     */
    DATABASE,

    /**
     * Schema（仅 PostgreSQL）
     */
    SCHEMA,

    /**
     * 表
     */
    TABLE,

    /**
     * 视图
     */
    VIEW,

    /**
     * 物化视图（仅 PostgreSQL）
     */
    MATERIALIZED_VIEW,

    /**
     * 列
     */
    COLUMN,

    /**
     * Redis 逻辑数据库
     */
    REDIS_DB,

    /**
     * Redis Key 前缀策略
     */
    KEY_PREFIX_POLICY
}
