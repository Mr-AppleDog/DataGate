package org.dromara.db.core.domain;

import java.time.Instant;

/**
 * 慢查询标准化记录（docs/07）。
 * 原始 SQL 在写入存储前必须完成敏感字面量清理；本对象不含未脱敏样例参数。
 *
 * @param sourceKey            来源唯一键（采集幂等）
 * @param dataSourceId         数据源
 * @param databaseName         库名（可空）
 * @param fingerprint          归一化指纹
 * @param normalizedStatement  归一化语句
 * @param sanitizedSample      脱敏后的样例（可空）
 * @param startedAt            语句开始时间（UTC）
 * @param durationMicros       耗时（微秒）
 * @param lockWaitMicros       锁等待（微秒）
 * @param rowsExamined         扫描行数
 * @param rowsReturned         返回行数
 * @param clientAddress        客户端地址
 * @param dbUser               数据库账号名（平台专用监控账号之外的业务账号仅记录名称，不记录密码）
 * @author DataGate
 */
public record SlowQueryRecord(
    String sourceKey,
    Long dataSourceId,
    String databaseName,
    String fingerprint,
    String normalizedStatement,
    String sanitizedSample,
    Instant startedAt,
    long durationMicros,
    long lockWaitMicros,
    long rowsExamined,
    long rowsReturned,
    String clientAddress,
    String dbUser
) {
}
