-- =============================================================================
-- V4: M1 安全底座 — 审计事件与凭据保险箱（docs/04 第 3.4/3.5/6 节，docs/08 第 6/9 节）
--
-- 1. dbg_audit_event：不可变审计事件，按月分区，哈希链防篡改（AUD-001/004/006）
-- 2. dbg_audit_archive：审计归档批次与根哈希
-- 3. dbg_credential / dbg_credential_version：凭据保险箱，AES-256-GCM 信封加密（CRED-001~004）
--
-- 约束说明：
-- - 审计表禁止 UPDATE/DELETE（触发器强制 + 应用层不提供修改接口）
-- - 分区表主键必须包含分区键，故 PK=(id, occurred_at)；event_id 由应用保证全局唯一（UUID）
-- - 凭据版本表不允许更新密文，轮换始终插入新版本
-- =============================================================================

-- ---------- 1. 审计事件（按月分区，追加写，哈希链） ----------
CREATE TABLE dbg_audit_event (
    id              int8          NOT NULL,
    event_id        varchar(36)   NOT NULL,
    category        varchar(32)   NOT NULL,
    action          varchar(64)   NOT NULL,
    actor_id        int8          NULL,
    actor_snapshot  jsonb         NULL,
    target_type     varchar(32)   NULL,
    target_id       varchar(64)   NULL,
    target_snapshot jsonb         NULL,
    result          varchar(16)   NOT NULL,
    source_ip       varchar(64)   NULL,
    user_agent      varchar(512)  NULL,
    trace_id        varchar(64)   NULL,
    details         jsonb         NULL,
    occurred_at     timestamptz   NOT NULL,
    retention_class varchar(16)   NOT NULL DEFAULT 'ONE_YEAR',
    chain_key       varchar(16)   NOT NULL,
    previous_hash   varchar(64)   NOT NULL,
    event_hash      varchar(64)   NOT NULL,
    CONSTRAINT pk_dbg_audit_event PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE  dbg_audit_event IS 'DataGate 不可变审计事件（追加写，哈希链防篡改）';
COMMENT ON COLUMN dbg_audit_event.event_id IS '事件全局唯一 ID（UUID）';
COMMENT ON COLUMN dbg_audit_event.category IS 'LOGIN/AUTH/QUERY/EXPORT/CHANGE/CREDENTIAL/CONFIG/SECURITY';
COMMENT ON COLUMN dbg_audit_event.result IS 'SUCCESS/FAILURE/DENIED/UNKNOWN';
COMMENT ON COLUMN dbg_audit_event.details IS '扩展明细（禁止包含查询结果与秘密）';
COMMENT ON COLUMN dbg_audit_event.retention_class IS 'ONE_YEAR/THREE_YEARS';
COMMENT ON COLUMN dbg_audit_event.chain_key IS '哈希链分片键（UTC 日，yyyyMMdd）';
COMMENT ON COLUMN dbg_audit_event.previous_hash IS '同分片前一事件哈希';
COMMENT ON COLUMN dbg_audit_event.event_hash IS '本事件哈希';

CREATE TABLE dbg_audit_event_2026_08 PARTITION OF dbg_audit_event
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');
CREATE TABLE dbg_audit_event_2026_09 PARTITION OF dbg_audit_event
    FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');
CREATE TABLE dbg_audit_event_2026_10 PARTITION OF dbg_audit_event
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
CREATE TABLE dbg_audit_event_2026_11 PARTITION OF dbg_audit_event
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE dbg_audit_event_2026_12 PARTITION OF dbg_audit_event
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
-- 兜底分区：防止分区维护任务缺位时审计写入失败（失败关闭的反面：审计必须可写）
CREATE TABLE dbg_audit_event_default PARTITION OF dbg_audit_event DEFAULT;

CREATE INDEX idx_dbg_audit_event_chain ON dbg_audit_event (chain_key, id);
CREATE INDEX idx_dbg_audit_event_actor ON dbg_audit_event (actor_id, occurred_at);
CREATE INDEX idx_dbg_audit_event_target ON dbg_audit_event (target_type, target_id, occurred_at);
CREATE INDEX idx_dbg_audit_event_event_id ON dbg_audit_event (event_id);

-- 审计事件不可变：禁止 UPDATE/DELETE（AUD-004 的数据库层兜底）
CREATE OR REPLACE FUNCTION dbg_audit_immutable() RETURNS trigger AS $func$
BEGIN
    RAISE EXCEPTION 'dbg_audit_event is append-only (AUD-004)';
END;
$func$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dbg_audit_event_immutable
    BEFORE UPDATE OR DELETE ON dbg_audit_event
    FOR EACH ROW EXECUTE FUNCTION dbg_audit_immutable();

-- ---------- 2. 审计归档批次 ----------
CREATE TABLE dbg_audit_archive (
    id              int8          NOT NULL PRIMARY KEY,
    range_start     timestamptz   NOT NULL,
    range_end       timestamptz   NOT NULL,
    record_count    int8          NOT NULL,
    root_hash       varchar(64)   NOT NULL,
    object_key      varchar(512)  NOT NULL,
    file_hash       varchar(64)   NOT NULL,
    status          varchar(16)   NOT NULL DEFAULT 'CREATED',
    verified_at     timestamptz   NULL,
    created_at      timestamptz   NOT NULL DEFAULT now()
);

COMMENT ON TABLE dbg_audit_archive IS '审计归档批次：时间范围、记录数、根哈希、对象存储位置与校验时间';

-- ---------- 3. 凭据保险箱 ----------
CREATE TABLE dbg_credential (
    id                 int8         NOT NULL PRIMARY KEY,
    tenant_id          varchar(20)  NOT NULL DEFAULT '000000',
    data_source_id     int8         NOT NULL,
    purpose            varchar(16)  NOT NULL,
    username           varchar(255) NOT NULL,
    active_version_id  int8         NULL,
    status             varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    last_verified_at   timestamptz  NULL,
    rotate_due_at      timestamptz  NULL,
    create_dept        int8         NULL,
    create_by          int8         NULL,
    create_time        timestamptz  NULL,
    update_by          int8         NULL,
    update_time        timestamptz  NULL,
    del_flag           char(1)      NOT NULL DEFAULT '0'
);

COMMENT ON TABLE  dbg_credential IS '凭据保险箱主表（CRED-001：查询/变更/监控分账号）';
COMMENT ON COLUMN dbg_credential.purpose IS 'QUERY/CHANGE/MONITOR';
COMMENT ON COLUMN dbg_credential.username IS '可受限显示的用户名（非秘密）';
COMMENT ON COLUMN dbg_credential.status IS 'ACTIVE/DISABLED/INVALID';

CREATE UNIQUE INDEX uk_dbg_cred_ds_purpose ON dbg_credential (data_source_id, purpose) WHERE del_flag = '0';
CREATE INDEX idx_dbg_cred_datasource ON dbg_credential (data_source_id);

CREATE TABLE dbg_credential_version (
    id                 int8         NOT NULL PRIMARY KEY,
    credential_id      int8         NOT NULL,
    version_no         int4         NOT NULL,
    ciphertext         bytea        NOT NULL,
    nonce              bytea        NOT NULL,
    wrapped_dek        bytea        NOT NULL,
    dek_nonce          bytea        NOT NULL,
    algorithm          varchar(32)  NOT NULL DEFAULT 'AES-256-GCM',
    key_version        varchar(64)  NOT NULL,
    secret_fingerprint varchar(64)  NOT NULL,
    status             varchar(16)  NOT NULL DEFAULT 'PENDING',
    verified_at        timestamptz  NULL,
    activated_at       timestamptz  NULL,
    retired_at         timestamptz  NULL,
    created_by         int8         NULL,
    created_at         timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  dbg_credential_version IS '凭据密文版本（信封加密：wrapped_dek 由外置 KEK 包裹，docs/08 第 6.1 节）';
COMMENT ON COLUMN dbg_credential_version.ciphertext IS 'DEK 加密后的秘密密文（含 GCM tag）';
COMMENT ON COLUMN dbg_credential_version.wrapped_dek IS 'KEK 包裹后的 DEK（KEK 轮换只重包裹本字段）';
COMMENT ON COLUMN dbg_credential_version.secret_fingerprint IS '不可逆指纹，用于重复检测，不可反推明文';
COMMENT ON COLUMN dbg_credential_version.status IS 'PENDING/VERIFIED/ACTIVE/RETIRED/INVALID';

CREATE UNIQUE INDEX uk_dbg_cred_version_no ON dbg_credential_version (credential_id, version_no);
CREATE INDEX idx_dbg_cred_version_cred ON dbg_credential_version (credential_id, status);
