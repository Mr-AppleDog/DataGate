package org.dromara.db.core.domain;

import org.dromara.db.core.enums.ExecutionStatus;

/**
 * 变更执行结果（docs/06 §13，M5-02）。
 *
 * @param executionNo      执行号
 * @param status           终态 SUCCEEDED/FAILED/UNKNOWN
 * @param affectedRows     影响行数合计
 * @param statementResults 逐语句结果 JSON [{statementHash,status,affectedRows,errorCode,durationMs}]
 * @param errorCode        失败错误码
 * @param durationMs       耗时
 * @author DataGate
 */
public record ChangeResult(
    String executionNo,
    ExecutionStatus status,
    long affectedRows,
    String statementResults,
    String errorCode,
    long durationMs
) {

    public static ChangeResult failed(String executionNo, String errorCode, long durationMs) {
        return new ChangeResult(executionNo, ExecutionStatus.FAILED, 0, null, errorCode, durationMs);
    }
}
