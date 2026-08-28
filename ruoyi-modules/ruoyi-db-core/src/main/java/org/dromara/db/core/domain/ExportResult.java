package org.dromara.db.core.domain;

import org.dromara.db.core.enums.ExecutionStatus;

/**
 * 导出执行结果（docs/06 §12）。
 *
 * @param executionNo      执行号
 * @param status           终态（SUCCEEDED/FAILED/CANCELED/TIMED_OUT/UNKNOWN）
 * @param rowCount         导出行数
 * @param resultBytes      导出字节数
 * @param objectKey        加密对象键（成功时）
 * @param fileHash         文件 SHA-256（成功时）
 * @param encryptionKeyRef 信封 DEK 引用（成功时）
 * @param errorCode        失败错误码
 * @param durationMs       耗时
 * @author DataGate
 */
public record ExportResult(
    String executionNo,
    ExecutionStatus status,
    long rowCount,
    long resultBytes,
    String objectKey,
    String fileHash,
    String encryptionKeyRef,
    String errorCode,
    long durationMs
) {

    public static ExportResult failed(String executionNo, String errorCode, long durationMs) {
        return new ExportResult(executionNo, ExecutionStatus.FAILED, 0, 0, null, null, null, errorCode, durationMs);
    }
}
