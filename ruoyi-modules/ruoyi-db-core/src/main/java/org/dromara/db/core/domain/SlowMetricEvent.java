package org.dromara.db.core.domain;

/**
 * 慢查询指标事件（docs/07 §2 数据流：标准化→聚合→规则评估）。
 *
 * <p>由 observability 在桶聚合后发布（桶指标），或在采集后发布（采集器健康，collectorHealth=true，
 * 只看 consecutiveFailures）。alert 评估服务据此匹配规则、生成/抑制告警事件。</p>
 *
 * <p>normalizedStatement 已脱敏（来自归一化引擎），事件不得携带原 SQL/Redis value/凭据。</p>
 *
 * @param dataSourceId         数据源
 * @param slowSourceId         慢查询来源
 * @param fingerprintId        指纹 ID（可空，采集器健康事件为 null）
 * @param fingerprint          portableFingerprint（可空）
 * @param normalizedStatement  脱敏归一化语句（可空）
 * @param engine               引擎类型
 * @param database             库名（可空）
 * @param environment          环境标签（prod/dev，由发布方解析 dataSource 填充）
 * @param windowStartMillis    窗口开始毫秒（采集器健康事件为 0）
 * @param windowEndMillis      窗口结束毫秒
 * @param eventCount           窗内事件数
 * @param errorCount           错误数
 * @param totalDurationMicros  窗内总耗时微秒
 * @param maxDurationMicros    窗内单次最大耗时微秒
 * @param p95DurationMicros    窗内 P95 微秒（可合并草图近似）
 * @param lockWaitMicros       锁等待微秒（可空=缺失）
 * @param rowsExamined        扫描行（可空）
 * @param rowsReturned        返回行（可空）
 * @param firstSeen            指纹首次出现
 * @param consecutiveFailures  采集器连续失败次数（COLLECTOR 规则用）
 * @param collectorHealth      是否采集器健康事件（true 时只评估 COLLECTOR_FAILURE 规则）
 * @author DataGate
 */
public record SlowMetricEvent(
    Long dataSourceId,
    Long slowSourceId,
    Long fingerprintId,
    String fingerprint,
    String normalizedStatement,
    String engine,
    String database,
    String environment,
    long windowStartMillis,
    long windowEndMillis,
    long eventCount,
    long errorCount,
    long totalDurationMicros,
    long maxDurationMicros,
    long p95DurationMicros,
    Long lockWaitMicros,
    Long rowsExamined,
    Long rowsReturned,
    boolean firstSeen,
    int consecutiveFailures,
    boolean collectorHealth
) {
}
