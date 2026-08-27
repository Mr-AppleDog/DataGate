package org.dromara.db.core.enums;

/**
 * 资源动作（docs/03 第 4 节动作模型）。
 * 动作不自动包含其他动作：QUERY 不包含 EXPORT，REDIS_READ 不包含 REDIS_SCAN。
 *
 * @author DataGate
 */
public enum DbAction {

    // ===== 通用动作 =====
    /**
     * 在目录和搜索中看到资源
     */
    DISCOVER,

    /**
     * 查看字段、索引、类型等元数据
     */
    METADATA_READ,

    /**
     * 维护 Owner，不包含数据读取
     */
    OWNER_MANAGE,

    /**
     * 查看该资源的慢查询聚合
     */
    SLOW_READ,

    /**
     * 查看慢查询样例（默认参数脱敏）
     */
    SLOW_SAMPLE_READ,

    // ===== 关系型动作 =====
    /**
     * 执行允许的只读查询
     */
    QUERY,

    /**
     * 获取执行计划（安全 EXPLAIN，PG 默认禁止 ANALYZE）
     */
    EXPLAIN,

    /**
     * 创建并下载受控导出
     */
    EXPORT,

    /**
     * 查看指定敏感列明文
     */
    COLUMN_UNMASK,

    /**
     * 经工单执行 DML
     */
    CHANGE_DML,

    /**
     * 经工单执行 DDL
     */
    CHANGE_DDL,

    /**
     * 管理类语句（GRANT/REVOKE/KILL/SET GLOBAL/SET PERSIST/VACUUM FULL/RESET MASTER/PURGE 等，docs/06 §5.2）。
     * 对普通用户默认不可授权（无 grant 路径=默认拒绝）。
     */
    ADMIN,

    /**
     * 代码执行类（CALL/DO/存储过程/函数执行/匿名块，docs/06 §5.2）。P0 普通用户禁止。
     */
    CODE,

    // ===== Redis 动作 =====
    /**
     * 按授权前缀浏览 Key
     */
    REDIS_SCAN,

    /**
     * 读取类型、TTL、长度和值
     */
    REDIS_READ,

    /**
     * 经工单写值、修改 TTL
     */
    REDIS_WRITE,

    /**
     * 经工单删除 Key
     */
    REDIS_DELETE,

    /**
     * 极少数受控管理命令，仅 DBA 特殊工单
     */
    REDIS_ADMIN
}
