package org.dromara.db.core.domain;

import org.dromara.db.core.enums.ExecutionStatus;

/**
 * 执行结果元数据（不承载查询结果正文）。
 *
 * @param executionNo  客户端可见执行号
 * @param status       执行状态
 * @param durationMs   耗时
 * @param rowCount     返回行数
 * @param resultBytes  返回字节数
 * @param truncated    是否被平台限制截断
 * @param errorCode    失败时的平台标准错误码名
 * @author DataGate
 */
public record ExecutionResultMeta(
    String executionNo,
    ExecutionStatus status,
    long durationMs,
    long rowCount,
    long resultBytes,
    boolean truncated,
    String errorCode
) {
}
