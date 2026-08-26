package org.dromara.db.core.error;

/**
 * DataGate 统一业务错误码（docs/05 第 3 节）。
 *
 * <p>数字段划分：41000-41999 IAM；42000-42999 AUTH；43000-43999 RESOURCE；
 * 44000-44999 CREDENTIAL；45000-45999 QUERY；46000-46999 REDIS；
 * 47000-47999 WORKFLOW；48000-48999 EXPORT/CHANGE；49000-49999 SLOW/ALERT。</p>
 *
 * <p>对外响应使用 RuoYi 统一 envelope：code=数字码，data.errorCode=本枚举名，
 * 生产响应不得携带数据库堆栈、JDBC URL、数据库用户名或 SQL 参数。</p>
 *
 * @author DataGate
 */
public enum DbErrorCode {

    // ================= IAM 41000-41999 =================
    IAM_MFA_REQUIRED(41001, 401, "当前操作需要完成双因素认证", false),
    IAM_REAUTH_REQUIRED(41002, 401, "当前操作需要 5 分钟内二次认证", false),
    IAM_ACCOUNT_LOCKED(41003, 401, "账号已锁定", false),
    IAM_PASSWORD_POLICY_VIOLATION(41004, 400, "密码不符合复杂度策略", false),
    IAM_PASSWORD_CHANGE_REQUIRED(41005, 403, "首次登录须修改初始密码", false),

    // ================= AUTH 42000-42999 =================
    AUTH_RESOURCE_DENIED(42001, 403, "当前操作未获得资源授权", false),
    AUTH_GRANT_EXPIRED(42002, 403, "授权已过期", false),
    AUTH_POLICY_VERSION_STALE(42003, 409, "授权版本已失效，请重试", true),
    AUTH_RESOURCE_UNDISCOVERABLE(42004, 404, "资源不存在或不可见", false),

    // ================= RESOURCE 43000-43999 =================
    RESOURCE_DISABLED(43001, 409, "数据源已禁用", false),
    RESOURCE_CAPABILITY_UNSUPPORTED(43002, 400, "数据源不支持该能力", false),
    RESOURCE_ARCHIVED(43003, 409, "数据源已归档，不可恢复", false),
    RESOURCE_STATE_CONFLICT(43004, 409, "数据源状态不允许该操作", false),
    RESOURCE_SSRF_BLOCKED(43005, 400, "连接地址未通过网络白名单校验", false),

    // ================= CREDENTIAL 44000-44999 =================
    CREDENTIAL_INVALID(44001, 400, "凭据验证失败", false),
    CREDENTIAL_ROTATION_CONFLICT(44002, 409, "凭据轮换冲突", true),
    CREDENTIAL_DISABLED(44003, 409, "凭据已禁用", false),
    CREDENTIAL_KEK_UNAVAILABLE(44004, 503, "密钥服务不可用", true),

    // ================= QUERY 45000-45999 =================
    QUERY_PARSE_FAILED(45001, 400, "语句解析失败", false),
    QUERY_UNSAFE_STATEMENT(45002, 422, "语句不符合安全规则", false),
    QUERY_LIMIT_EXCEEDED(45003, 422, "超出平台或环境限制", false),
    QUERY_TIMEOUT(45004, 504, "查询执行超时", true),
    QUERY_CANCELED(45005, 409, "查询已取消", false),
    QUERY_CONCURRENCY_EXCEEDED(45006, 429, "并发查询数超限", true),
    QUERY_PLAN_EXPIRED(45007, 409, "执行计划已过期", false),
    QUERY_ENGINE_UNAVAILABLE(45008, 503, "目标数据源不可用", true),

    // ================= REDIS 46000-46999 =================
    REDIS_COMMAND_DENIED(46001, 422, "Redis 命令被拒绝", false),
    REDIS_KEY_PREFIX_DENIED(46002, 403, "Key 前缀未授权", false),
    REDIS_SCAN_LIMIT_EXCEEDED(46003, 422, "SCAN 超出元素或字节上限", false),

    // ================= WORKFLOW 47000-47999 =================
    WORKFLOW_SELF_APPROVAL_DENIED(47001, 403, "申请人不能审批本人工单", false),
    WORKFLOW_STATE_CONFLICT(47002, 409, "工单状态已变化，请刷新", true),
    WORKFLOW_SQL_MODIFIED(47003, 409, "审批后 SQL 不允许修改", false),

    // ================= EXPORT / CHANGE 48000-48999 =================
    EXPORT_LIMIT_EXCEEDED(48001, 422, "导出超出限制", false),
    EXPORT_TICKET_EXPIRED(48002, 410, "下载票据已过期", false),
    CHANGE_PRECHECK_FAILED(48003, 422, "变更预检查未通过", false),
    CHANGE_WINDOW_NOT_REACHED(48004, 409, "未到执行窗口", false),
    CHANGE_IDEMPOTENCY_CONFLICT(48005, 409, "重复的执行请求", false),

    // ================= SLOW / ALERT 49000-49999 =================
    COLLECTOR_CURSOR_CONFLICT(49001, 409, "采集游标冲突", true),
    NOTIFICATION_DELIVERY_FAILED(49002, 502, "通知投递失败", true),
    ALERT_RULE_INVALID(49003, 400, "告警规则不合法", false),

    // ================= SYSTEM =================
    INTERNAL_ERROR(50000, 500, "平台内部错误", true);

    /**
     * 数字错误码（对外 envelope 的 code）
     */
    private final int code;

    /**
     * 建议 HTTP 状态码
     */
    private final int httpStatus;

    /**
     * 对外提示（不得包含内部实现细节）
     */
    private final String message;

    /**
     * 是否可重试
     */
    private final boolean retryable;

    DbErrorCode(int code, int httpStatus, String message, boolean retryable) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
        this.retryable = retryable;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
