package org.dromara.db.core.domain;

import java.time.Instant;

/**
 * 慢查询采集游标。游标更新使用乐观锁，重启/轮转/重置可检测。
 *
 * @param sourceId        慢查询来源 ID
 * @param partitionKey    分区键（同一来源多分片采集）
 * @param cursor          游标内容（JSON，结构由采集器实现定义）
 * @param lastRecordTime  最后一条记录时间
 * @param version         乐观锁版本
 * @author DataGate
 */
public record CollectorCursor(
    Long sourceId,
    String partitionKey,
    String cursor,
    Instant lastRecordTime,
    long version
) {
}
