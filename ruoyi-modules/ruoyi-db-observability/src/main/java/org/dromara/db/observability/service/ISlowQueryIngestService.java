package org.dromara.db.observability.service;

import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.SlowQueryRecord;

import java.util.List;

/**
 * 慢查询落盘服务（docs/07 §4.1 先落原始事件再提交游标，§4.2 幂等去重）。
 *
 * 职责：指纹 upsert（首见/末见）、样例幂等 insert（source_key 去重）、
 * 5 分钟桶累积（count/total/min/max/avg，p95/p99 保守近似见 ADR-009）、游标乐观锁提交。
 * 原始 SQL 加密由采集器切片 B 引入；本切片 sanitizedSample 明文落盘已足够治理展示。
 *
 * @author DataGate
 */
public interface ISlowQueryIngestService {

    /**
     * 落盘一批慢查询记录并提交游标。
     *
     * @param slowSourceId  慢查询来源 ID
     * @param partitionKey  分区键
     * @param records       标准化记录（normalizedStatement 为空时用 sanitizedSample 兜底归一化）
     * @param nextCursor    下一游标（null 不提交）
     */
    IngestResult ingest(Long slowSourceId, String partitionKey, List<SlowQueryRecord> records, CollectorCursor nextCursor);

    /**
     * @param accepted      新增样例数
     * @param duplicate      重复跳过数（source_key 已存在）
     * @param cursorUpdated 游标是否更新成功
     * @param cursorConflict 游标乐观锁冲突（COLLECTOR_CURSOR_CONFLICT）
     */
    record IngestResult(int accepted, int duplicate, boolean cursorUpdated, boolean cursorConflict) {
    }
}
