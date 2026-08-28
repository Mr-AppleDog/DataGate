-- =============================================================================
-- V16: M5 SQL 变更工单（docs/04 §5.7 dbg_change_order/execution，CHG-001，docs/03 §10.3/§4.2 CHANGE_DML/DDL）
--
-- 不可变 SQL 快照+目标+预检查+影响估算+回滚方案+流程实例+执行窗口；
-- 每次尝试记录执行节点/凭据ID/状态/影响行数/遮蔽错误。禁止失败后自动重放非幂等变更。
-- =============================================================================
CREATE TABLE dbg_change_order (
    id                   int8         NOT NULL,
    tenant_id            varchar(20)  NOT NULL DEFAULT '000000',
    request_no           varchar(32)  NOT NULL,
    applicant_id         int8         NOT NULL,
    data_source_id       int8         NOT NULL,
    database_name        varchar(256) NULL,
    schema_name          varchar(256) NULL,
    change_type          varchar(16)  NOT NULL,
    statement_encrypted  text         NULL,
    statement_hash       varchar(64)  NULL,
    fingerprint          varchar(128) NULL,
    resource_snapshot    jsonb        NULL,
    precheck_result      jsonb        NULL,
    rollback_plan        text         NULL,
    impact_summary       text         NULL,
    execution_window_start timestamptz NULL,
    execution_window_end  timestamptz NULL,
    workflow_instance_id int8         NULL,
    status               varchar(24)  NOT NULL DEFAULT 'DRAFT',
    version              int4         NOT NULL DEFAULT 0,
    create_by            int8         NULL,
    create_time          timestamptz  NULL,
    update_by            int8         NULL,
    update_time          timestamptz  NULL,
    del_flag             char(1)      NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_change_order PRIMARY KEY (id)
);
COMMENT ON TABLE  dbg_change_order IS 'SQL 变更工单（docs/04 §5.7）：不可变快照+两级审批+执行窗口+专用CHANGE凭据';
COMMENT ON COLUMN dbg_change_order.statement_encrypted IS '不可变 SQL 快照密文（审批后不可篡改，SQL 改动回 DRAFT）';
COMMENT ON COLUMN dbg_change_order.precheck_result IS '预检查结果 JSON：risks[]、severity';
COMMENT ON COLUMN dbg_change_order.status IS 'DRAFT/PRECHECKING/PRECHECKED/PENDING_APPROVAL/APPROVED/SCHEDULED/RUNNING/SUCCEEDED/FAILED/UNKNOWN/PRECHECK_FAILED/REJECTED/CANCELED（docs/05 §4.5）';
CREATE UNIQUE INDEX uk_dbg_change_request_no ON dbg_change_order (request_no) WHERE del_flag = '0';
CREATE INDEX idx_dbg_change_applicant ON dbg_change_order (applicant_id, status);
CREATE INDEX idx_dbg_change_status ON dbg_change_order (status, execution_window_start);

CREATE TABLE dbg_change_execution (
    id              int8         NOT NULL,
    order_id        int8         NOT NULL,
    attempt_no      int4         NOT NULL,
    execution_node  varchar(64)  NULL,
    credential_id   int8         NULL,
    started_at      timestamptz  NOT NULL DEFAULT now(),
    finished_at     timestamptz  NULL,
    status          varchar(24)  NOT NULL,
    affected_rows   int8         NOT NULL DEFAULT 0,
    error_code      varchar(64)  NULL,
    error_summary   varchar(1000) NULL,
    statement_results jsonb      NULL,
    idempotency_key varchar(128) NOT NULL,
    CONSTRAINT pk_dbg_change_execution PRIMARY KEY (id)
);
COMMENT ON TABLE  dbg_change_execution IS '变更执行尝试（docs/04 §5.7）：每次尝试+逐语句结果；禁止失败后自动重放非幂等';
COMMENT ON COLUMN dbg_change_execution.statement_results IS '逐语句结果 JSON：[{statementHash,status,affectedRows,errorCode,durationMs}]';
COMMENT ON COLUMN dbg_change_execution.error_summary IS '遮蔽后的错误摘要（不含明文参数/结果）';
CREATE UNIQUE INDEX uk_dbg_change_exec_idem ON dbg_change_execution (idempotency_key);
CREATE INDEX idx_dbg_change_exec_order ON dbg_change_execution (order_id, attempt_no);

-- =============================================================================
-- CHANGE_APPROVAL 两级审批流程（docs/03 §10.3：申请人 → 业务负责人 → DBA）
-- start → apply → biz_approve → dba_approve → end；固定主键 9201-9210。
-- =============================================================================
INSERT INTO flow_definition
    (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, activity_status, ext, create_time, create_by, del_flag, tenant_id)
VALUES
    (9201, 'dbg_change_approval', '变更审批', 'CLASSICS', 'datagate', '1', 1, 'N', 1, NULL, now(), 'datagate', '0', '000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, "version", ext, del_flag, tenant_id, create_time)
VALUES
    (9202, 0, 9201, 'start',        '开始',     NULL,    '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now()),
    (9203, 1, 9201, 'apply',        '申请人',   '',       '0.000', '360,200|360,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now()),
    (9204, 1, 9201, 'biz_approve',  '业务负责人','',       '0.000', '540,200|540,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9205, 1, 9201, 'dba_approve',  'DBA',      '',       '0.000', '720,200|720,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9206, 2, 9201, 'end',          '结束',     NULL,    '0.000', '980,200|980,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_skip
    (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, del_flag, tenant_id, create_time)
VALUES
    (9207, 9201, 'start',        0, 'apply',        1, NULL, 'PASS', NULL, '220,200;310,200', '0', '000000', now()),
    (9208, 9201, 'apply',        1, 'biz_approve',  1, NULL, 'PASS', NULL, '410,200;490,200', '0', '000000', now()),
    (9209, 9201, 'biz_approve',  1, 'dba_approve',  1, NULL, 'PASS', NULL, '590,200;670,200', '0', '000000', now()),
    (9210, 9201, 'dba_approve',  1, 'end',          2, NULL, 'PASS', NULL, '770,200;860,200', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;
