-- =============================================================================
-- V11: M4 慢查询存储底座 — 采集来源/游标/指纹/样例/桶/治理日志（docs/04 第 7 节，SLOW）
--
-- docs/07 §3 统一 SlowEvent 字段在持久层落地：
--   - 双指纹：portable_fingerprint（引擎无关 SHA-256）+ native_fingerprint（MySQL digest/PG queryid/Redis 平台算）
--   - parser_version：解析器升级后可追溯，不静默覆盖历史指纹（docs/07 §5.1）
--   - raw_sql_encrypted：原 SQL 单独数据密钥加密，仅 SLOW_SQL_RAW_VIEW 权限 + 二次认证后可看（docs/07 §5.2）
--   - ingest_quality：COMPLETE/PARTIAL/ESTIMATED/AGGREGATED/PARSE_FAILED（缺失值 NULL，不用 0 冒充）
--
-- 偏差（本切片，ADR-009 记录）：
--   - dbg_slow_sample 暂为普通表 + 高索引，未按 occurred_at 分区；分区化留 M6 容量压测阶段
--     通过 ALTER PARTITION 迁移落地，避免 M4 早期 MyBatis-Plus 与分区表主键/唯一约束交互复杂度。
--   - raw_sql_encrypted 列已建，但本切片不写入原文（采集器切片 B 引入单独数据密钥加密服务）。
-- =============================================================================

-- 1. dbg_slow_source：慢查询采集来源配置（docs/04 §7.1）
CREATE TABLE dbg_slow_source (
    id                       int8          NOT NULL,
    tenant_id                varchar(20)   NOT NULL DEFAULT '000000',
    data_source_id           int8          NOT NULL,
    collect_type             varchar(32)   NOT NULL,
    collect_interval_seconds int           NOT NULL DEFAULT 60,
    status                   varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    monitor_credential_id    int8          NULL,
    last_success_at          timestamptz   NULL,
    lag_seconds              int           NULL,
    consecutive_failures     int           NOT NULL DEFAULT 0,
    last_error_code          varchar(64)   NULL,
    last_error_summary       varchar(500)  NULL,
    create_dept              int8          NULL,
    create_by                int8          NULL,
    create_time              timestamptz   NULL,
    update_by                int8          NULL,
    update_time              timestamptz   NULL,
    del_flag                 char(1)       NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_slow_source PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_slow_source IS '慢查询采集来源（SLOW，docs/04 §7.1）：采集类型/间隔/状态/监控凭据引用';
COMMENT ON COLUMN dbg_slow_source.collect_type IS 'MYSQL_SLOW_LOG/MYSQL_PERF_SCHEMA/ALIYUN_API/PG_STAT_STATEMENTS/PG_LOG/REDIS_SLOWLOG（docs/07 §4）';
COMMENT ON COLUMN dbg_slow_source.collect_interval_seconds IS '采集间隔（1-15 分钟，docs/07 §4.1）';
COMMENT ON COLUMN dbg_slow_source.status IS 'ACTIVE/PAUSED/ERROR（失联 3 周期告警 docs/07 §14）';
COMMENT ON COLUMN dbg_slow_source.monitor_credential_id IS '独立监控账号凭据，不得与查询/变更账号共用（docs/07 §4.1）';
COMMENT ON COLUMN dbg_slow_source.lag_seconds IS '采集滞后秒数（last_success 距今）';
COMMENT ON COLUMN dbg_slow_source.consecutive_failures IS '连续失败次数（≥3 触发 COLLECTOR 告警）';
COMMENT ON COLUMN dbg_slow_source.last_error_code IS '平台标准错误码（不含秘密/堆栈）';
COMMENT ON COLUMN dbg_slow_source.last_error_summary IS '错误摘要（秘密遮蔽后，≤500 字符）';

CREATE UNIQUE INDEX uk_dbg_slow_source_ds_type
    ON dbg_slow_source (data_source_id, collect_type) WHERE del_flag = '0';
CREATE INDEX idx_dbg_slow_source_status ON dbg_slow_source (status) WHERE del_flag = '0';
CREATE INDEX idx_dbg_slow_source_ds ON dbg_slow_source (data_source_id) WHERE del_flag = '0';

-- 2. dbg_slow_cursor：增量采集游标（docs/04 §7.2，乐观锁）
CREATE TABLE dbg_slow_cursor (
    id                    int8          NOT NULL,
    tenant_id             varchar(20)   NOT NULL DEFAULT '000000',
    slow_source_id        int8          NOT NULL,
    partition_key         varchar(64)   NOT NULL DEFAULT 'default',
    cursor                jsonb         NULL,
    last_record_time      timestamptz   NULL,
    last_success_at       timestamptz   NULL,
    consecutive_failures  int           NOT NULL DEFAULT 0,
    version               int8          NOT NULL DEFAULT 0,
    create_time           timestamptz   NULL,
    update_time           timestamptz   NULL,
    CONSTRAINT pk_dbg_slow_cursor PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_slow_cursor IS '慢查询采集游标（SLOW，docs/04 §7.2）：乐观锁、重启/轮转/重置可检测';
COMMENT ON COLUMN dbg_slow_cursor.partition_key IS '分区键（同源多分片采集，如 PG dbid、Redis 实例纪元）';
COMMENT ON COLUMN dbg_slow_cursor.cursor IS '游标 JSON（结构由采集器定义，含上游位置与快照基线）';
COMMENT ON COLUMN dbg_slow_cursor.version IS '乐观锁版本（COLLECTOR_CURSOR_CONFLICT docs/05 §4.6）';

CREATE UNIQUE INDEX uk_dbg_slow_cursor_src_part
    ON dbg_slow_cursor (slow_source_id, partition_key);
CREATE INDEX idx_dbg_slow_cursor_src ON dbg_slow_cursor (slow_source_id);

-- 3. dbg_slow_fingerprint：指纹与治理状态（docs/04 §7.3，治理状态机 docs/05 §4.6）
CREATE TABLE dbg_slow_fingerprint (
    id                    int8          NOT NULL,
    tenant_id             varchar(20)   NOT NULL DEFAULT '000000',
    data_source_id        int8          NOT NULL,
    database_name         varchar(256)  NULL,
    engine                varchar(32)   NOT NULL,
    fingerprint           varchar(128)  NOT NULL,
    native_fingerprint    varchar(128)  NULL,
    parser_version       varchar(32)   NOT NULL,
    normalized_statement text          NOT NULL,
    risk_flags           jsonb         NULL,
    governance_status    varchar(24)   NOT NULL DEFAULT 'DISCOVERED',
    assignee_id          int8          NULL,
    first_seen_at        timestamptz   NOT NULL,
    last_seen_at         timestamptz   NOT NULL,
    create_dept          int8          NULL,
    create_by            int8          NULL,
    create_time          timestamptz   NULL,
    update_by            int8          NULL,
    update_time          timestamptz   NULL,
    del_flag             char(1)       NOT NULL DEFAULT '0',
    version              int           NOT NULL DEFAULT 0,
    CONSTRAINT pk_dbg_slow_fingerprint PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_slow_fingerprint IS '慢查询指纹与治理状态（SLOW，docs/04 §7.3 + docs/07 §5.1 双指纹）';
COMMENT ON COLUMN dbg_slow_fingerprint.fingerprint IS 'portableFingerprint（引擎无关 SHA-256，跨实例归并）';
COMMENT ON COLUMN dbg_slow_fingerprint.native_fingerprint IS 'MySQL digest / PG queryid / Redis 平台算（可空）';
COMMENT ON COLUMN dbg_slow_fingerprint.parser_version IS '解析器版本，升级后可追溯，不静默覆盖历史指纹（docs/07 §5.1）';
COMMENT ON COLUMN dbg_slow_fingerprint.normalized_statement IS '去常量/脱敏后的归一化模板（默认展示，docs/07 §5.2）';
COMMENT ON COLUMN dbg_slow_fingerprint.risk_flags IS '全表扫描/无 WHERE/无界分页等标记（jsonb）';
COMMENT ON COLUMN dbg_slow_fingerprint.governance_status IS 'DISCOVERED/CLAIMED/IN_PROGRESS/PENDING_VERIFY/RESOLVED/IGNORED（docs/05 §4.6）';
COMMENT ON COLUMN dbg_slow_fingerprint.assignee_id IS '治理负责人';
COMMENT ON COLUMN dbg_slow_fingerprint.version IS '治理状态乐观锁（状态迁移携 version，docs/05 §5）';

CREATE UNIQUE INDEX uk_dbg_slow_fp_ds_db_eng_fp
    ON dbg_slow_fingerprint (data_source_id, database_name, engine, fingerprint) WHERE del_flag = '0';
CREATE INDEX idx_dbg_slow_fp_status ON dbg_slow_fingerprint (governance_status) WHERE del_flag = '0';
CREATE INDEX idx_dbg_slow_fp_assignee ON dbg_slow_fingerprint (assignee_id) WHERE del_flag = '0' AND assignee_id IS NOT NULL;
CREATE INDEX idx_dbg_slow_fp_last_seen ON dbg_slow_fingerprint (last_seen_at);
CREATE INDEX idx_dbg_slow_fp_ds ON dbg_slow_fingerprint (data_source_id) WHERE del_flag = '0';

-- 4. dbg_slow_sample：逐次慢查询样例（docs/04 §7.4 + docs/07 §3 SlowEvent）
CREATE TABLE dbg_slow_sample (
    id                   int8          NOT NULL,
    tenant_id            varchar(20)   NOT NULL DEFAULT '000000',
    slow_source_id       int8          NOT NULL,
    fingerprint_id       int8          NOT NULL,
    source_key           varchar(256)  NOT NULL,
    source_event_id      varchar(128)  NULL,
    occurred_at          timestamptz   NOT NULL,
    collected_at         timestamptz   NOT NULL,
    database_name        varchar(256)  NULL,
    duration_micros      int8          NOT NULL,
    lock_wait_micros    int8          NULL,
    rows_examined        int8          NULL,
    rows_returned        int8          NULL,
    affected_rows        int8          NULL,
    cpu_micros           int8          NULL,
    io_bytes             int8          NULL,
    temp_bytes           int8          NULL,
    client_address       varchar(128)  NULL,
    db_user              varchar(128)  NULL,
    application_name     varchar(128)  NULL,
    sanitized_sample     text          NULL,
    raw_sql_encrypted    text          NULL,
    raw_access_level     varchar(16)   NOT NULL DEFAULT 'MASKED',
    sample_rate          int           NULL,
    ingest_quality       varchar(16)   NOT NULL DEFAULT 'COMPLETE',
    create_time          timestamptz   NULL,
    CONSTRAINT pk_dbg_slow_sample PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_slow_sample IS '慢查询逐次样例（SLOW，docs/04 §7.4）：保留 30 天，分区化见 ADR-009';
COMMENT ON COLUMN dbg_slow_sample.source_key IS '来源唯一键（采集幂等，sourceId + sourceEventId 或稳定复合键）';
COMMENT ON COLUMN dbg_slow_sample.source_event_id IS '上游可用唯一键（MySQL thread_id/log行号、PG queryid+call、Redis slowlog id）';
COMMENT ON COLUMN dbg_slow_sample.occurred_at IS '数据库事件时间（UTC 存储，界面按用户时区显示，docs/07 §4.1）';
COMMENT ON COLUMN dbg_slow_sample.collected_at IS '平台采集时间（UTC）';
COMMENT ON COLUMN dbg_slow_sample.duration_micros IS '执行耗时微秒（缺失值 NULL，不用 0 冒充，docs/07 §3）';
COMMENT ON COLUMN dbg_slow_sample.lock_wait_micros IS '锁等待微秒（可缺失）';
COMMENT ON COLUMN dbg_slow_sample.sanitized_sample IS '脱敏后样例（明文可存，docs/07 §5.2）';
COMMENT ON COLUMN dbg_slow_sample.raw_sql_encrypted IS '原 SQL 单独数据密钥加密（仅 SLOW_SQL_RAW_VIEW + 二次认证可看，日志/通知永不发原文）';
COMMENT ON COLUMN dbg_slow_sample.raw_access_level IS 'MASKED/RAW_VIEW（docs/07 §5.2）';
COMMENT ON COLUMN dbg_slow_sample.ingest_quality IS 'COMPLETE/PARTIAL/ESTIMATED/AGGREGATED/PARSE_FAILED（docs/07 §3）';

CREATE UNIQUE INDEX uk_dbg_slow_sample_source_key
    ON dbg_slow_sample (slow_source_id, source_key);
CREATE INDEX idx_dbg_slow_sample_fp_time ON dbg_slow_sample (fingerprint_id, occurred_at);
CREATE INDEX idx_dbg_slow_sample_src_time ON dbg_slow_sample (slow_source_id, occurred_at);
CREATE INDEX idx_dbg_slow_sample_occurred ON dbg_slow_sample (occurred_at);

-- 5. dbg_slow_bucket：时间窗口聚合（docs/04 §7.5 + docs/07 §6.2）
CREATE TABLE dbg_slow_bucket (
    id                    int8          NOT NULL,
    tenant_id             varchar(20)   NOT NULL DEFAULT '000000',
    fingerprint_id       int8          NOT NULL,
    granularity          varchar(8)    NOT NULL,
    bucket_start         timestamptz   NOT NULL,
    bucket_end           timestamptz   NOT NULL,
    event_count          int           NOT NULL DEFAULT 0,
    error_count          int           NOT NULL DEFAULT 0,
    duration_min        int8          NULL,
    duration_max        int8          NULL,
    duration_avg        int8          NULL,
    duration_p95        int8          NULL,
    duration_p99        int8          NULL,
    total_duration      int8          NOT NULL DEFAULT 0,
    total_lock_wait     int8          NULL,
    total_rows_examined int8          NULL,
    total_rows_returned int8          NULL,
    affected_users      int           NULL,
    affected_databases  jsonb         NULL,
    completeness        varchar(16)   NOT NULL DEFAULT 'COMPLETE',
    first_seen_at       timestamptz   NULL,
    last_seen_at        timestamptz   NULL,
    published_at        timestamptz   NULL,
    create_time         timestamptz   NULL,
    update_time         timestamptz   NULL,
    CONSTRAINT pk_dbg_slow_bucket PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_slow_bucket IS '慢查询窗口聚合（SLOW，docs/04 §7.5）：5分钟/小时/天';
COMMENT ON COLUMN dbg_slow_bucket.granularity IS 'FIVE_MIN/HOUR/DAY（在线保留 180天/24月/3年，docs/07 §6.1）';
COMMENT ON COLUMN dbg_slow_bucket.completeness IS 'COMPLETE/PARTIAL（来源完整性标志）';
COMMENT ON COLUMN dbg_slow_bucket.duration_p95 IS 'P95（可合并草图/直方图近似，记录算法版本见 risk_flags）';

CREATE UNIQUE INDEX uk_dbg_slow_bucket_fp_gran_start
    ON dbg_slow_bucket (fingerprint_id, granularity, bucket_start);
CREATE INDEX idx_dbg_slow_bucket_gran_start ON dbg_slow_bucket (granularity, bucket_start);
CREATE INDEX idx_dbg_slow_bucket_fp ON dbg_slow_bucket (fingerprint_id, bucket_start);

-- 6. dbg_slow_governance_log：治理状态/评论/验证 追加日志（docs/04 §7.6 + docs/07 §10）
CREATE TABLE dbg_slow_governance_log (
    id                  int8          NOT NULL,
    tenant_id           varchar(20)   NOT NULL DEFAULT '000000',
    fingerprint_id      int8          NOT NULL,
    action              varchar(32)   NOT NULL,
    from_status         varchar(24)   NULL,
    to_status           varchar(24)   NULL,
    old_assignee_id     int8          NULL,
    new_assignee_id     int8          NULL,
    comment             text          NULL,
    due_at              timestamptz   NULL,
    metrics             jsonb         NULL,
    related_change_id   int8          NULL,
    operator_id         int8          NULL,
    create_time         timestamptz   NULL,
    CONSTRAINT pk_dbg_slow_governance_log PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_slow_governance_log IS '慢查询治理日志（SLOW，docs/04 §7.6）：状态/负责人/评论/验证窗口/前后指标，全部追加写不可覆盖';
COMMENT ON COLUMN dbg_slow_governance_log.action IS 'STATUS_CHANGE/ASSIGN/COMMENT/OPTIMIZE_NOTE/VERIFY_WINDOW/METRICS_BEFORE/METRICS_AFTER/REOPEN';
COMMENT ON COLUMN dbg_slow_governance_log.metrics IS '前后指标快照（jsonb，含阈值/趋势/证据摘要）';
COMMENT ON COLUMN dbg_slow_governance_log.related_change_id IS '关联变更工单（docs/07 §10 IN_PROGRESS）';

CREATE INDEX idx_dbg_slow_gov_log_fp ON dbg_slow_governance_log (fingerprint_id, create_time);
