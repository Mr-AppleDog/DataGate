-- =============================================================================
-- V15: M5 导出工单（docs/04 §5.6 dbg_export_job，EXP-001，docs/03 §10.2/§4.2 EXPORT）
--
-- 导出申请/查询指纹/资源快照/限制/状态/行数/字节数/对象存储Key/文件哈希/加密信息引用/下载次数/过期/删除。
-- 不保存可公开访问 URL；SQL 以密文锁定（创建时重新解析+鉴权+锁定策略版本，docs/06 §12）。
-- =============================================================================
CREATE TABLE dbg_export_job (
    id                   int8         NOT NULL,
    tenant_id            varchar(20)  NOT NULL DEFAULT '000000',
    request_no           varchar(32)  NOT NULL,
    applicant_id         int8         NOT NULL,
    data_source_id       int8         NOT NULL,
    database_name        varchar(256) NULL,
    schema_name          varchar(256) NULL,
    statement_encrypted  text         NULL,
    statement_hash       varchar(64)  NULL,
    fingerprint          varchar(128) NULL,
    resource_snapshot    jsonb        NULL,
    limits               jsonb        NULL,
    decision_id          varchar(64)  NULL,
    masking_level        varchar(16)  NULL,
    status               varchar(24)  NOT NULL DEFAULT 'DRAFT',
    row_count            int8         NOT NULL DEFAULT 0,
    result_bytes         int8         NOT NULL DEFAULT 0,
    object_key           varchar(128) NULL,
    file_hash            varchar(64)  NULL,
    encryption_key_ref   varchar(128) NULL,
    download_count       int4         NOT NULL DEFAULT 0,
    ticket_hash          varchar(128) NULL,
    ticket_expires_at    timestamptz  NULL,
    expires_at           timestamptz  NULL,
    deleted_at           timestamptz  NULL,
    workflow_instance_id int8         NULL,
    version              int4         NOT NULL DEFAULT 0,
    create_by            int8         NULL,
    create_time          timestamptz  NULL,
    update_by            int8         NULL,
    update_time          timestamptz  NULL,
    del_flag             char(1)      NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_export_job PRIMARY KEY (id)
);
COMMENT ON TABLE  dbg_export_job IS '导出工单（docs/04 §5.6）：独立 EXPORT 权限+两级审批；不保存可公开访问URL；SQL密文锁定';
COMMENT ON COLUMN dbg_export_job.statement_encrypted IS '申请时锁定的 SQL 密文（重新解析+鉴权后冻结，不可篡改）';
COMMENT ON COLUMN dbg_export_job.object_key IS '随机对象键（非公开URL），服务端加密';
COMMENT ON COLUMN dbg_export_job.ticket_hash IS '一次性下载票据哈希（默认5min有效，单次）';
COMMENT ON COLUMN dbg_export_job.expires_at IS '对象生命周期（默认24h，最长7天）';
COMMENT ON COLUMN dbg_export_job.status IS 'DRAFT/PENDING_APPROVAL/APPROVED/QUEUED/RUNNING/SUCCEEDED/EXPIRED/DELETED/REJECTED/CANCELED/FAILED（docs/05 §4.4）';
CREATE UNIQUE INDEX uk_dbg_export_request_no ON dbg_export_job (request_no) WHERE del_flag = '0';
CREATE INDEX idx_dbg_export_applicant ON dbg_export_job (applicant_id, status);
CREATE INDEX idx_dbg_export_status ON dbg_export_job (status, expires_at);

-- =============================================================================
-- EXPORT_APPROVAL 两级审批流程定义（docs/03 §10.2：申请人 → 资源 Owner → DBA）
-- start → apply(申请人) → owner_approve(资源负责人) → dba_approve(DBA) → end
-- 两审批节点 permission_flag 留空，运行时由流程变量 PASS:owner_approve / PASS:dba_approve
-- 锁定办理人。未指定则无人可办——失败关闭。固定主键 9101-9110。
-- =============================================================================
INSERT INTO flow_definition
    (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, activity_status, ext, create_time, create_by, del_flag, tenant_id)
VALUES
    (9101, 'dbg_export_approval', '导出审批', 'CLASSICS', 'datagate', '1', 1, 'N', 1, NULL, now(), 'datagate', '0', '000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, "version", ext, del_flag, tenant_id, create_time)
VALUES
    (9102, 0, 9101, 'start',        '开始',     NULL,    '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now()),
    (9103, 1, 9101, 'apply',        '申请人',   '',       '0.000', '360,200|360,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now()),
    (9104, 1, 9101, 'owner_approve','资源负责人','',       '0.000', '540,200|540,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9105, 1, 9101, 'dba_approve',  'DBA',      '',       '0.000', '720,200|720,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9106, 2, 9101, 'end',          '结束',     NULL,    '0.000', '980,200|980,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_skip
    (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, del_flag, tenant_id, create_time)
VALUES
    (9107, 9101, 'start',        0, 'apply',        1, NULL, 'PASS', NULL, '220,200;310,200', '0', '000000', now()),
    (9108, 9101, 'apply',        1, 'owner_approve',1, NULL, 'PASS', NULL, '410,200;490,200', '0', '000000', now()),
    (9109, 9101, 'owner_approve',1, 'dba_approve',  1, NULL, 'PASS', NULL, '590,200;670,200', '0', '000000', now()),
    (9110, 9101, 'dba_approve',  1, 'end',          2, NULL, 'PASS', NULL, '770,200;860,200', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;
