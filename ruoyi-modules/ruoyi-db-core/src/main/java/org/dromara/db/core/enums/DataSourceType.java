package org.dromara.db.core.enums;

/**
 * 受管数据源引擎类型（docs/00 第 3.3 节 P0 范围）
 *
 * @author DataGate
 */
public enum DataSourceType {

    /**
     * MySQL、自建 MySQL、阿里云 RDS MySQL、PolarDB MySQL
     */
    MYSQL,

    /**
     * PostgreSQL、自建 PostgreSQL、阿里云 RDS PostgreSQL
     */
    POSTGRESQL,

    /**
     * Redis（RESP 协议）
     */
    REDIS,

    /**
     * 阿里云 Tair（RESP 协议兼容，增强采集经 connector-aliyun）
     */
    TAIR
}
