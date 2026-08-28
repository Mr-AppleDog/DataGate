# ADR-009：M4 慢查询 SPI 记录字段扩展与存储分区偏差

- 状态：已确认（M4 切片 A）
- 日期：2026-08-26 基线
- 关联文档：docs/07 §3（SlowEvent）、§5.1（双指纹）、§6.1（分区）；docs/04 §7

## 背景

M0 为 M4 预留了 SlowQueryRecord SPI 记录与 SlowQueryProvider 接口，但预留字段不足以表达 docs/07 §3 统一 SlowEvent 的要求：

- 缺 nativeFingerprint（引擎原生指纹）、parserVersion（解析器版本可追溯）；
- 缺 sourceEventId（上游唯一键）、occurredAt/collectedAt（数据库时间 vs 采集时间）；
- 缺 affectedRows/cpuMicros/ioBytes/tempBytes/sampleRate/ingestQuality；
- 原有 lockWaitMicros/rowsExamined/rowsReturned 为基本类型 long，无法表达"缺失"（docs/07 §3 要求缺失值 NULL，不用 0 冒充）。

docs/04 §7.4 要求 dbg_slow_sample 按发生日期日/月分区。

## 决策

1. 扩展 SlowQueryRecord 承载 docs/07 §3 全字段：
   - 新增 sourceEventId/engineType/nativeFingerprint/parserVersion/occurredAt/collectedAt/affectedRows/cpuMicros/ioBytes/tempBytes/applicationName/sampleRate/ingestQuality；
   - 可缺失指标改包装类型 Long/Integer（null = 缺失），durationMicros 为核心必填保留 long；
   - portableFingerprint 映射 fingerprint，nativeFingerprint 单独列。
   - M0–M3 期间所有连接器 slowQueryProvider() 均返回 Optional.empty()，无既有 SlowQueryRecord 构造点，扩展无破坏性。

2. dbg_slow_sample 暂为普通表 + 高索引，未按 occurred_at 分区：
   - 分区表与 MyBatis-Plus 主键（雪花 ID）/唯一约束交互复杂，M4 早期容量未达百万事件/日，普通表 + (fingerprint_id, occurred_at)/(slow_source_id, occurred_at)/(occurred_at) 索引足够；
   - 分区化留 M6 容量压测阶段，通过 ALTER TABLE ... PARTITION BY RANGE (occurred_at) + 历史数据迁移落地，不阻塞 M4 功能闭环。

3. raw_sql_encrypted 列已建但切片 A 不写入：
   - 原 SQL 单独数据密钥加密服务在采集器切片 B 引入（采集器持有原 SQL），切片 A 的 sanitizedSample（明文脱敏）已足够治理展示。

## 影响

- 采集器切片 B（MySQL/PG/Redis SlowQueryProvider 实现）按新字段填充；
- 治理切片 D 的"前后指标"读 dbg_slow_bucket，不依赖 sample 分区；
- M6 压测前需执行 sample 分区迁移脚本并回归采集写入路径。

## 风险

- 分区迁移期间若已有线上数据，需停写或在线迁移；M4 仅试运行，数据量可控。
- ingestQuality=PARSE_FAILED 时 normalizedStatement 为保守哈希模板，指纹仍可聚合，治理不丢失。
