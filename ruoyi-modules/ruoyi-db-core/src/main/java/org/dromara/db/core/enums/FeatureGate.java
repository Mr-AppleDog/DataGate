package org.dromara.db.core.enums;

/**
 * 功能开关项（docs/09 §14.3，M6-01a）。
 *
 * <p>仅功能灰度（按环境/数据源控制连接器/导出/变更/新解析器版本逐步开放）。
 * <b>安全规则（脱敏、只读、审批、TOTP、双人、审计失败关闭）不在此枚举，不受功能开关管控，
 * 不得通过普通功能开关永久关闭</b>（docs/09 §14.3）。</p>
 *
 * @author DataGate
 */
public enum FeatureGate {

    /** MySQL 连接器（P0 主引擎，通常 always-on） */
    CONNECTOR_MYSQL,
    /** PostgreSQL 连接器灰度 */
    CONNECTOR_POSTGRESQL,
    /** Redis 连接器灰度 */
    CONNECTOR_REDIS,
    /** 导出工单灰度 */
    EXPORT,
    /** DML 变更工单灰度 */
    CHANGE_DML,
    /** DDL 变更工单灰度 */
    CHANGE_DDL,
    /** Redis 写变更工单灰度 */
    REDIS_WRITE,
    /** 紧急访问灰度 */
    EMERGENCY_ACCESS,
    /** 新解析器版本灰度（升级驱动/解析器时按数据源逐步切换） */
    PARSER_NEW_VERSION
}
