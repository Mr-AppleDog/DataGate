-- =============================================================================
-- V12: M4 规则、告警事件与通知通道 — alert_rule/event/notification_channel/delivery（docs/04 §8，SLOW/ALERT）
--
-- docs/07 §7 规则：受控表单生成规则表达式（不允许管理员直接编写脚本），复杂条件用受版本控制 JSON DSL。
-- docs/07 §8 评估：每分钟评估已完成桶，2 分钟迟到窗；alertDedupKey=ruleId+sourceId+fingerprint+window；
--   同一告警持续触发更新计数与峰值不重复轰炸；默认抑制 15 分钟；维护静默不得静默采集器故障与平台安全告警。
-- docs/07 §9 通知：钉钉/SMTP/Webhook SPI；outbox 持久化；指数退避重试最多 8 次；4xx→DEAD，429/5xx 重试；
--   Webhook 必须 HTTPS + 签名/时间戳/防重放 + 域名/IP 白名单；通道密钥与数据源凭据同等级加密托管。
-- docs/05 §4.7 事件状态机：PENDING→FIRING→ACKNOWLEDGED→RESOLVED；FIRING→SILENCED→FIRING/RESOLVED。
-- =============================================================================

-- 1. dbg_alert_rule：告警规则（docs/04 §8.1）
CREATE TABLE dbg_alert_rule (
    id                     int8          NOT NULL,
    tenant_id              varchar(20)   NOT NULL DEFAULT '000000',
    name                   varchar(200)  NOT NULL,
    severity               varchar(16)    NOT NULL,
    scope                  jsonb         NOT NULL DEFAULT '{}'::jsonb,
    metric                 varchar(32)   NOT NULL,
    operator               varchar(4)    NOT NULL,
    threshold              numeric(20,3) NOT NULL,
    duration_seconds       int           NOT NULL DEFAULT 0,
    first_seen_only        char(1)       NOT NULL DEFAULT '0',
    dedup_window_seconds   int           NOT NULL DEFAULT 900,
    silence_config         jsonb         NULL,
    routing                jsonb         NULL,
    config_dsl            jsonb         NULL,
    status                 varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    version                int           NOT NULL DEFAULT 1,
    create_dept            int8          NULL,
    create_by              int8          NULL,
    create_time            timestamptz   NULL,
    update_by              int8          NULL,
    update_time            timestamptz   NULL,
    del_flag               char(1)       NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_alert_rule PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_alert_rule IS '告警规则（SLOW/ALERT，docs/04 §8.1 + docs/07 §7）：表单 DSL 版本化';
COMMENT ON COLUMN dbg_alert_rule.severity IS 'P1/P2/P3/COLLECTOR（docs/07 §7.2）';
COMMENT ON COLUMN dbg_alert_rule.scope IS '作用范围 JSON（environment/tags/dataSourceId/database/fingerprint/engine）';
COMMENT ON COLUMN dbg_alert_rule.metric IS 'SINGLE_MAX_DURATION/WINDOW_COUNT/WINDOW_P95/WINDOW_P99/WINDOW_TOTAL_DURATION/LOCK_WAIT/SCAN_RETURN_RATIO/BASELINE_SURGE/FIRST_SEEN/COLLECTOR_FAILURE';
COMMENT ON COLUMN dbg_alert_rule.threshold IS '阈值（duration 类用微秒，与 sample.duration_micros 一致）';
COMMENT ON COLUMN dbg_alert_rule.duration_seconds IS '评估窗口秒数（5分钟=300）';
COMMENT ON COLUMN dbg_alert_rule.dedup_window_seconds IS '去重抑制窗口（默认 900=15 分钟，docs/07 §8）';
COMMENT ON COLUMN dbg_alert_rule.silence_config IS '静默配置 JSON（开始/结束/原因/审批，不得静默采集器故障与平台安全告警）';
COMMENT ON COLUMN dbg_alert_rule.routing IS '通知路由 JSON（目标通道/角色：DBA/RESOURCE_OWNER/PLATFORM_OPS）';
COMMENT ON COLUMN dbg_alert_rule.config_dsl IS '受版本控制规则 DSL JSON（复杂条件，升级可追溯）';
COMMENT ON COLUMN dbg_alert_rule.version IS '规则版本（修改必须版本化并写审计，docs/07 §7.2）';

CREATE INDEX idx_dbg_alert_rule_status ON dbg_alert_rule (status) WHERE del_flag = '0';
CREATE INDEX idx_dbg_alert_rule_severity ON dbg_alert_rule (severity) WHERE del_flag = '0';

-- 2. dbg_alert_event：告警事件（docs/04 §8.2）
CREATE TABLE dbg_alert_event (
    id                int8          NOT NULL,
    tenant_id         varchar(20)   NOT NULL DEFAULT '000000',
    rule_id           int8          NOT NULL,
    dedup_key         varchar(256)  NOT NULL,
    data_source_id    int8          NULL,
    fingerprint_id    int8          NULL,
    fingerprint       varchar(128)  NULL,
    severity          varchar(16)    NOT NULL,
    status            varchar(16)   NOT NULL DEFAULT 'PENDING',
    first_fired_at    timestamptz   NULL,
    last_fired_at     timestamptz   NULL,
    trigger_count     int           NOT NULL DEFAULT 1,
    current_value     numeric(20,3) NULL,
    threshold         numeric(20,3) NULL,
    window_start      timestamptz   NULL,
    window_end        timestamptz   NULL,
    resolved_at       timestamptz   NULL,
    assignee_id       int8          NULL,
    silence_until     timestamptz   NULL,
    evidence_summary  varchar(1000) NULL,
    create_dept       int8          NULL,
    create_by         int8          NULL,
    create_time       timestamptz   NULL,
    update_by         int8          NULL,
    update_time       timestamptz   NULL,
    version           int           NOT NULL DEFAULT 0,
    CONSTRAINT pk_dbg_alert_event PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_alert_event IS '告警事件（SLOW/ALERT，docs/04 §8.2 + docs/07 §8）：去重键抑制轰炸';
COMMENT ON COLUMN dbg_alert_event.dedup_key IS 'ruleId + sourceId + fingerprint + window（docs/07 §8）';
COMMENT ON COLUMN dbg_alert_event.status IS 'PENDING/FIRING/ACKNOWLEDGED/RESOLVED/SILENCED（docs/05 §4.7）';
COMMENT ON COLUMN dbg_alert_event.trigger_count IS '同一告警持续触发累计计数（更新峰值不重复轰炸）';
COMMENT ON COLUMN dbg_alert_event.evidence_summary IS '证据摘要（触发指标/阈值/窗口/趋势，脱敏后）';
COMMENT ON COLUMN dbg_alert_event.version IS '事件状态乐观锁（状态迁移携 version）';

CREATE INDEX idx_dbg_alert_event_status ON dbg_alert_event (status);
CREATE INDEX idx_dbg_alert_event_rule ON dbg_alert_event (rule_id, last_fired_at);
CREATE INDEX idx_dbg_alert_event_ds ON dbg_alert_event (data_source_id, last_fired_at);
-- 唯一活动事件约束：同一 dedup_key 同时最多一个未解决事件（docs/04 §8.2，部分唯一索引）
CREATE UNIQUE INDEX uk_dbg_alert_event_active
    ON dbg_alert_event (dedup_key) WHERE status <> 'RESOLVED';

-- 3. dbg_notification_channel：通知通道（docs/04 §8.3）
CREATE TABLE dbg_notification_channel (
    id                 int8          NOT NULL,
    tenant_id          varchar(20)   NOT NULL DEFAULT '000000',
    type               varchar(16)   NOT NULL,
    name               varchar(200)  NOT NULL,
    config             jsonb         NULL,
    secret_reference   varchar(128)  NULL,
    status             varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    last_verified_at   timestamptz   NULL,
    create_dept        int8          NULL,
    create_by          int8          NULL,
    create_time        timestamptz   NULL,
    update_by          int8          NULL,
    update_time        timestamptz   NULL,
    del_flag           char(1)       NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_notification_channel PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_notification_channel IS '通知通道（SLOW/ALERT，docs/04 §8.3 + docs/07 §9）：秘密不进 config';
COMMENT ON COLUMN dbg_notification_channel.type IS 'DINGTALK/SMTP/WEBHOOK/WECHAT_WORK/FEISHU（docs/07 §9）';
COMMENT ON COLUMN dbg_notification_channel.config IS '非秘密配置 JSON（webhook URL/smtp host+from+to，不含密码类键）';
COMMENT ON COLUMN dbg_notification_channel.secret_reference IS '秘密引用（credentialId，与数据源凭据同等级加密托管）';

CREATE INDEX idx_dbg_notification_channel_status ON dbg_notification_channel (status) WHERE del_flag = '0';

-- 4. dbg_notification_delivery：通知投递（docs/04 §8.4）
CREATE TABLE dbg_notification_delivery (
    id                  int8          NOT NULL,
    tenant_id           varchar(20)   NOT NULL DEFAULT '000000',
    event_id            int8          NOT NULL,
    channel_id          int8          NOT NULL,
    template_version    varchar(32)   NOT NULL,
    target_summary      varchar(200)  NULL,
    status              varchar(16)   NOT NULL DEFAULT 'PENDING',
    attempt_count       int           NOT NULL DEFAULT 0,
    next_retry_at       timestamptz   NULL,
    response_code       varchar(16)   NULL,
    response_summary    varchar(500)  NULL,
    rendered_body_hash  varchar(128)  NULL,
    created_at          timestamptz   NULL,
    completed_at        timestamptz   NULL,
    CONSTRAINT pk_dbg_notification_delivery PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_notification_delivery IS '通知投递（SLOW/ALERT，docs/04 §8.4 + docs/07 §9）：outbox 指数退避重试死信';
COMMENT ON COLUMN dbg_notification_delivery.status IS 'PENDING/SENDING/SENT/FAILED/DEAD（4xx→DEAD，429/5xx重试）';
COMMENT ON COLUMN dbg_notification_delivery.attempt_count IS '尝试次数（最多 8 次，指数退避）';
COMMENT ON COLUMN dbg_notification_delivery.response_summary IS 'HTTP/SMTP 响应摘要（脱敏后）';
COMMENT ON COLUMN dbg_notification_delivery.rendered_body_hash IS '渲染正文哈希（只保留脱敏后渲染版本或哈希，不含原文）';

CREATE INDEX idx_dbg_notification_delivery_status ON dbg_notification_delivery (status, next_retry_at);
CREATE INDEX idx_dbg_notification_delivery_event ON dbg_notification_delivery (event_id);

-- =============================================================================
-- 默认规则（docs/07 §7.2）：P1/P2/P3/COLLECTOR，固定主键 9101–9104 避免运行时雪花 ID 冲突
-- 阈值单位微秒（与 dbg_slow_sample.duration_micros 一致）：30s=30000000, 5s=5000000, 3s=3000000, 300s=300000000
-- =============================================================================
INSERT INTO dbg_alert_rule
    (id, name, severity, scope, metric, operator, threshold, duration_seconds, first_seen_only, dedup_window_seconds, routing, status, version, create_time, create_by, del_flag, tenant_id)
VALUES
    (9101, '生产单次慢查询 ≥ 30 秒', 'P1', '{"environment":"prod"}'::jsonb,
        'SINGLE_MAX_DURATION', 'GE', 30000000, 0, '0', 900,
        '{"channels":[],"roles":["DBA","RESOURCE_OWNER"]}'::jsonb, 'ACTIVE', 1, now(), NULL, '0', '000000'),
    (9102, '生产 5 分钟总耗时 ≥ 300 秒', 'P1', '{"environment":"prod"}'::jsonb,
        'WINDOW_TOTAL_DURATION', 'GE', 300000000, 300, '0', 900,
        '{"channels":[],"roles":["DBA","RESOURCE_OWNER"]}'::jsonb, 'ACTIVE', 1, now(), NULL, '0', '000000'),
    (9103, '生产 p95 ≥ 5 秒且 5 分钟 ≥ 20 次', 'P2', '{"environment":"prod"}'::jsonb,
        'WINDOW_P95', 'GE', 5000000, 300, '0', 900,
        '{"channels":[],"roles":["RESOURCE_OWNER"]}'::jsonb, 'ACTIVE', 1, now(), NULL, '0', '000000'),
    (9104, '首次出现且单次 ≥ 3 秒', 'P3', '{}'::jsonb,
        'SINGLE_MAX_DURATION', 'GE', 3000000, 0, '1', 1800,
        '{"channels":[],"roles":["GOVERNANCE"]}'::jsonb, 'ACTIVE', 1, now(), NULL, '0', '000000'),
    (9105, '采集器连续 3 个周期采集失败', 'COLLECTOR', '{}'::jsonb,
        'COLLECTOR_FAILURE', 'GE', 3, 0, '0', 900,
        '{"channels":[],"roles":["PLATFORM_OPS"]}'::jsonb, 'ACTIVE', 1, now(), NULL, '0', '000000')
ON CONFLICT (id) DO NOTHING;
