package org.dromara.db.core.enums;

/**
 * 审计类别（docs/08 第 9.1 节）
 *
 * @author DataGate
 */
public enum AuditCategory {

    /**
     * 登录、退出、锁定、TOTP、会话撤销
     */
    LOGIN,

    /**
     * 授权申请、审批、授予、撤销、到期
     */
    AUTH,

    /**
     * 查询提交、拒绝、执行、取消、超时
     */
    QUERY,

    /**
     * 导出全状态
     */
    EXPORT,

    /**
     * DML/DDL/Redis 变更
     */
    CHANGE,

    /**
     * 凭据新增、验证、轮换、使用
     */
    CREDENTIAL,

    /**
     * 数据源、环境、元数据、资源配置
     */
    CONFIG,

    /**
     * 越权尝试、危险语句、频率限制、密钥事件
     */
    SECURITY,

    /**
     * 用户、角色、菜单等后台管理
     */
    ADMIN
}
