package org.dromara.db.core.domain;

import java.time.Instant;

/**
 * 慢查询标准化记录（docs/07 §3 统一 SlowEvent）。
 *
 * 采集器（SlowQueryProvider 实现）产出本对象，存储层据此落指纹、样例与桶聚合。
 * 原始 SQL 在写入存储前必须完成敏感字面量清理；本对象不含未脱敏样例参数。
 * 缺失的指标字段必须为 null 并以 ingestQuality 标注，不能用 0 冒充"没有消耗"。
 *
 * 双指纹（docs/07 §5.1）：
 * - fingerprint：portableFingerprint，引擎无关 SHA-256，便于跨实例归并；
 * - nativeFingerprint：引擎原生指纹（MySQL digest / PG queryid / Redis 平台算），可空；
 * - parserVersion：解析器版本，升级后可追溯，不静默覆盖历史指纹。
 *
 * @param sourceKey           来源唯一键（采集幂等：sourceId + sourceEventId 或稳定复合键）
 * @param sourceEventId       上游可用唯一键（可空）
 * @param dataSourceId        数据源
 * @param engineType          引擎类型（MYSQL/POSTGRESQL/REDIS/TAIR）
 * @param databaseName        库名（可空）
 * @param fingerprint         portableFingerprint（引擎无关哈希）
 * @param nativeFingerprint   引擎原生指纹（可空）
 * @param parserVersion       解析器版本（升级可追溯）
 * @param normalizedStatement 归一化语句（去常量/脱敏模板，默认展示）
 * @param sanitizedSample     脱敏后的样例（可空）
 * @param occurredAt          数据库事件时间（UTC 存储，界面按用户时区显示）
 * @param collectedAt         平台采集时间（UTC）
 * @param durationMicros      执行耗时（微秒，核心必填）
 * @param lockWaitMicros      锁等待微秒（可缺失）
 * @param rowsExamined       扫描行数（可缺失）
 * @param rowsReturned       返回行数（可缺失）
 * @param affectedRows       影响行数（可缺失）
 * @param cpuMicros          CPU 微秒（可缺失）
 * @param ioBytes            IO 字节（可缺失）
 * @param tempBytes          临时空间字节（可缺失）
 * @param clientAddress      客户端地址（可空，仅记录名不记密码）
 * @param dbUser             数据库账号名（可空）
 * @param applicationName    应用名（可空）
 * @param sampleRate         上游采样率（可空）
 * @param ingestQuality      COMPLETE/PARTIAL/ESTIMATED/AGGREGATED/PARSE_FAILED
 * @author DataGate
 */
public record SlowQueryRecord(
    String sourceKey,
    String sourceEventId,
    Long dataSourceId,
    String engineType,
    String databaseName,
    String fingerprint,
    String nativeFingerprint,
    String parserVersion,
    String normalizedStatement,
    String sanitizedSample,
    Instant occurredAt,
    Instant collectedAt,
    long durationMicros,
    Long lockWaitMicros,
    Long rowsExamined,
    Long rowsReturned,
    Long affectedRows,
    Long cpuMicros,
    Long ioBytes,
    Long tempBytes,
    String clientAddress,
    String dbUser,
    String applicationName,
    Integer sampleRate,
    String ingestQuality
) {
}
