-- =============================================================================
-- V17: M5 紧急访问（docs/03 §10.4、docs/10 M5-04，ALT-001）
--
-- 双人审批 + 最长 2h 临时授权 + 强制 TOTP + 事件编号 + 自动到期 + 即时通知 + 事后 24h 复盘。
-- 不允许续期，只能重新申请。来源 EMERGENCY。
-- =============================================================================
CREATE TABLE dbg_emergency_access (
    id                   int8         NOT NULL,
    tenant_id            varchar(20)  NOT NULL DEFAULT '000000',
    request_no           varchar(32)  NOT NULL,
    event_no             varchar(128) NOT NULL,
    applicant_id         int8         NOT NULL,
    approver1_id         int8         NOT NULL,
    approver2_id         int8         NOT NULL,
    target_resource_id   int8         NOT NULL,
    target_action        varchar(32)  NOT NULL,
    reason               text         NOT NULL,
    valid_from           timestamptz  NULL,
    valid_until          timestamptz  NULL,
    grant_id             int8         NULL,
    status               varchar(24)  NOT NULL DEFAULT 'DRAFT',
    post_mortem_due_at   timestamptz  NULL,
    post_mortem_content  text         NULL,
    post_mortem_at       timestamptz  NULL,
    workflow_instance_id int8         NULL,
    version              int4         NOT NULL DEFAULT 0,
    create_by            int8         NULL,
    create_time          timestamptz  NULL,
    update_by            int8         NULL,
    update_time          timestamptz  NULL,
    del_flag             char(1)      NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_emergency_access PRIMARY KEY (id)
);
COMMENT ON TABLE  dbg_emergency_access IS '紧急访问（docs/03 §10.4）：双人审批+2h临时授权+TOTP+事件编号+事后24h复盘；不续期';
COMMENT ON COLUMN dbg_emergency_access.event_no IS '事件编号（必填，外部事项号）';
COMMENT ON COLUMN dbg_emergency_access.valid_until IS '截止时间（最长 now+2h，不续期）';
COMMENT ON COLUMN dbg_emergency_access.status IS 'DRAFT/PENDING_APPROVAL/APPROVED/ACTIVE/EXPIRED/REVOKED/REJECTED/CANCELED/POST_MORTEM_PENDING/POST_MORTEM_DONE';
COMMENT ON COLUMN dbg_emergency_access.post_mortem_due_at IS '复盘截止（开通后24h内必须补复盘）';
CREATE UNIQUE INDEX uk_dbg_emergency_request_no ON dbg_emergency_access (request_no) WHERE del_flag = '0';
CREATE INDEX idx_dbg_emergency_applicant ON dbg_emergency_access (applicant_id, status);
CREATE INDEX idx_dbg_emergency_status ON dbg_emergency_access (status, valid_until);
CREATE INDEX idx_dbg_emergency_event ON dbg_emergency_access (event_no);

-- =============================================================================
-- EMERGENCY_APPROVAL 双人审批流程（docs/03 §10.4：申请人 → 审批人1 → 审批人2）
-- start → apply → approve1 → approve2 → end；固定主键 9301-9310。
-- 两审批人须不同且均非申请人（服务端强制）。
-- =============================================================================
INSERT INTO flow_definition
    (id, flow_code, flow_name, model_value, category, "version", is_publish, form_custom, activity_status, ext, create_time, create_by, del_flag, tenant_id)
VALUES
    (9301, 'dbg_emergency_approval', '紧急访问审批', 'CLASSICS', 'datagate', '1', 1, 'N', 1, NULL, now(), 'datagate', '0', '000000')
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_node
    (id, node_type, definition_id, node_code, node_name, permission_flag, node_ratio, coordinate, any_node_skip, listener_type, listener_path, form_custom, "version", ext, del_flag, tenant_id, create_time)
VALUES
    (9302, 0, 9301, 'start',    '开始',   NULL,    '0.000', '200,200|200,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now()),
    (9303, 1, 9301, 'apply',    '申请人', '',       '0.000', '360,200|360,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,file,copy"}]', '0', '000000', now()),
    (9304, 1, 9301, 'approve1', '审批人1', '',      '0.000', '540,200|540,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9305, 1, 9301, 'approve2', '审批人2', '',      '0.000', '720,200|720,200', NULL, NULL, NULL, 'N', '1', '[{"code":"ButtonPermissionEnum","value":"back,termination,copy,transfer,trust,file"}]', '0', '000000', now()),
    (9306, 2, 9301, 'end',      '结束',   NULL,    '0.000', '980,200|980,200', NULL, NULL, NULL, 'N', '1', '[]', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO flow_skip
    (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, coordinate, del_flag, tenant_id, create_time)
VALUES
    (9307, 9301, 'start',    0, 'apply',    1, NULL, 'PASS', NULL, '220,200;310,200', '0', '000000', now()),
    (9308, 9301, 'apply',    1, 'approve1', 1, NULL, 'PASS', NULL, '410,200;490,200', '0', '000000', now()),
    (9309, 9301, 'approve1', 1, 'approve2', 1, NULL, 'PASS', NULL, '590,200;670,200', '0', '000000', now()),
    (9310, 9301, 'approve2', 1, 'end',      2, NULL, 'PASS', NULL, '770,200;860,200', '0', '000000', now())
ON CONFLICT (id) DO NOTHING;
