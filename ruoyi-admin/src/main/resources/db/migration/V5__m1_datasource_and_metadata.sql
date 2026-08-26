-- =============================================================================
-- V5: M1 数据源资产与元数据（docs/04 第 3 节，RES-001~008）
--
-- 1. dbg_environment：环境（生产必须 CRITICAL 风险级）
-- 2. dbg_data_source：数据源结构化配置（不存秘密；连接串由服务端按白名单参数构造）
-- 3. dbg_tag / dbg_data_source_tag：标签（仅检索与告警路由，不隐含授权）
-- 4. dbg_resource：统一资源目录（授权始终引用 resource_id）
-- 5. dbg_metadata_sync_job：元数据同步任务
-- =============================================================================

-- ---------- 1. 环境 ----------
CREATE TABLE dbg_environment (
    id             int8         NOT NULL PRIMARY KEY,
    tenant_id      varchar(20)  NOT NULL DEFAULT '000000',
    code           varchar(32)  NOT NULL,
    name           varchar(64)  NOT NULL,
    risk_level     varchar(16)  NOT NULL DEFAULT 'LOW',
    default_policy jsonb        NULL,
    status         varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    remark         varchar(500) NULL,
    create_dept    int8         NULL,
    create_by      int8         NULL,
    create_time    timestamptz  NULL,
    update_by      int8         NULL,
    update_time    timestamptz  NULL,
    del_flag       char(1)      NOT NULL DEFAULT '0'
);
COMMENT ON TABLE dbg_environment IS '环境（RES-001）：生产环境 risk_level 必须为 CRITICAL';
CREATE UNIQUE INDEX uk_dbg_environment_code ON dbg_environment (code) WHERE del_flag = '0';

-- 预置环境：生产=CRITICAL（硬安全上限，不可通过普通配置降低）
INSERT INTO dbg_environment (id, code, name, risk_level, default_policy, status, remark, create_dept, create_by, create_time)
VALUES
 (1, 'dev',  '开发', 'LOW',      '{"maxRows":5000,"maxBytes":52428800,"maxExecutionSeconds":60}',  'ACTIVE', '开发环境', 103, 1, now()),
 (2, 'test', '测试', 'MEDIUM',   '{"maxRows":5000,"maxBytes":52428800,"maxExecutionSeconds":60}',  'ACTIVE', '测试环境', 103, 1, now()),
 (3, 'prod', '生产', 'CRITICAL', '{"maxRows":500,"maxBytes":10485760,"maxExecutionSeconds":30,"maskingLevel":"MASKED"}', 'ACTIVE', '生产环境（最高安全等级）', 103, 1, now());

-- ---------- 2. 数据源 ----------
CREATE TABLE dbg_data_source (
    id                 int8         NOT NULL PRIMARY KEY,
    tenant_id          varchar(20)  NOT NULL DEFAULT '000000',
    environment_id     int8         NOT NULL,
    type               varchar(32)  NOT NULL,
    name               varchar(128) NOT NULL,
    host               varchar(255) NOT NULL,
    port               int4         NOT NULL,
    default_database   varchar(128) NULL,
    connection_options jsonb        NULL,
    tls_mode           varchar(16)  NOT NULL DEFAULT 'PREFER',
    status             varchar(24)  NOT NULL DEFAULT 'DRAFT',
    owner_type         varchar(16)  NULL,
    owner_id           int8         NULL,
    policy_version     int8         NOT NULL DEFAULT 0,
    last_verified_at   timestamptz  NULL,
    last_error_code    varchar(64)  NULL,
    remark             varchar(500) NULL,
    version            int4         NOT NULL DEFAULT 0,
    create_dept        int8         NULL,
    create_by          int8         NULL,
    create_time        timestamptz  NULL,
    update_by          int8         NULL,
    update_time        timestamptz  NULL,
    del_flag           char(1)      NOT NULL DEFAULT '0'
);
COMMENT ON TABLE dbg_data_source IS '数据源（RES-002）：结构化字段保存，禁止任意 JDBC URL；凭据不入本表';
COMMENT ON COLUMN dbg_data_source.type IS 'MYSQL/POSTGRESQL/REDIS/TAIR';
COMMENT ON COLUMN dbg_data_source.status IS 'DRAFT/VERIFYING/ACTIVE/DISABLED/ERROR/ARCHIVED（docs/05 4.1）';
COMMENT ON COLUMN dbg_data_source.tls_mode IS 'DISABLE/PREFER/REQUIRE/VERIFY_CA/FULL';
COMMENT ON COLUMN dbg_data_source.last_error_code IS '平台标准错误码，不保存秘密或完整异常';

CREATE UNIQUE INDEX uk_dbg_ds_env_name ON dbg_data_source (environment_id, name) WHERE del_flag = '0';
CREATE INDEX idx_dbg_ds_type_status ON dbg_data_source (type, status);
CREATE INDEX idx_dbg_ds_owner ON dbg_data_source (owner_type, owner_id);

-- ---------- 3. 标签 ----------
CREATE TABLE dbg_tag (
    id          int8         NOT NULL PRIMARY KEY,
    tenant_id   varchar(20)  NOT NULL DEFAULT '000000',
    code        varchar(64)  NOT NULL,
    name        varchar(64)  NOT NULL,
    color       varchar(16)  NULL,
    create_by   int8         NULL,
    create_time timestamptz  NULL,
    update_by   int8         NULL,
    update_time timestamptz  NULL,
    del_flag    char(1)      NOT NULL DEFAULT '0'
);
CREATE UNIQUE INDEX uk_dbg_tag_code ON dbg_tag (code) WHERE del_flag = '0';

CREATE TABLE dbg_data_source_tag (
    data_source_id int8 NOT NULL,
    tag_id         int8 NOT NULL,
    CONSTRAINT pk_dbg_ds_tag PRIMARY KEY (data_source_id, tag_id)
);

-- ---------- 4. 统一资源目录 ----------
CREATE TABLE dbg_resource (
    id               int8         NOT NULL PRIMARY KEY,
    tenant_id        varchar(20)  NOT NULL DEFAULT '000000',
    data_source_id   int8         NOT NULL,
    parent_id        int8         NOT NULL DEFAULT 0,
    resource_type    varchar(32)  NOT NULL,
    physical_name    varchar(512) NOT NULL,
    normalized_name  varchar(512) NOT NULL,
    canonical_path   text         NOT NULL,
    metadata         jsonb        NULL,
    status           varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    metadata_version int8         NOT NULL DEFAULT 0,
    first_seen_at    timestamptz  NULL,
    last_seen_at     timestamptz  NULL,
    create_time      timestamptz  NULL,
    update_time      timestamptz  NULL
);
COMMENT ON TABLE dbg_resource IS '统一资源目录（docs/03 第 3 节）：授权始终引用 resource_id';
COMMENT ON COLUMN dbg_resource.resource_type IS 'DATA_SOURCE/DATABASE/SCHEMA/TABLE/VIEW/MATERIALIZED_VIEW/COLUMN/REDIS_DB/KEY_PREFIX_POLICY';
COMMENT ON COLUMN dbg_resource.status IS 'ACTIVE/DISABLED/DROPPED/UNKNOWN';

CREATE UNIQUE INDEX uk_dbg_resource_path ON dbg_resource (data_source_id, canonical_path);
CREATE INDEX idx_dbg_resource_parent ON dbg_resource (parent_id, resource_type, status);
CREATE INDEX idx_dbg_resource_name ON dbg_resource (data_source_id, normalized_name);
CREATE INDEX idx_dbg_resource_ds_type ON dbg_resource (data_source_id, resource_type, status);

-- ---------- 5. 元数据同步任务 ----------
CREATE TABLE dbg_metadata_sync_job (
    id               int8         NOT NULL PRIMARY KEY,
    data_source_id   int8         NOT NULL,
    trigger_type     varchar(16)  NOT NULL,
    status           varchar(16)  NOT NULL DEFAULT 'RUNNING',
    cursor           jsonb        NULL,
    metadata_version int8         NULL,
    started_at       timestamptz  NOT NULL DEFAULT now(),
    finished_at      timestamptz  NULL,
    found_count      int4         NOT NULL DEFAULT 0,
    updated_count    int4         NOT NULL DEFAULT 0,
    dropped_count    int4         NOT NULL DEFAULT 0,
    error_code       varchar(64)  NULL,
    error_summary    varchar(1000) NULL,
    task_id          varchar(64)  NULL,
    create_by        int8         NULL,
    create_time      timestamptz  NULL
);
COMMENT ON TABLE dbg_metadata_sync_job IS '元数据同步任务（RES-005）：错误详情经秘密遮蔽';
CREATE INDEX idx_dbg_sync_job_ds ON dbg_metadata_sync_job (data_source_id, started_at DESC);
