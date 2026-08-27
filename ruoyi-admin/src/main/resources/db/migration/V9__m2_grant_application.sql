-- V9 查询权限申请单（docs/03 §10.1、docs/04 §4，WF-001，M2-02）
-- 审批流由 WarmFlow 编排；本表承载申请明细与审批结果回填。
CREATE TABLE IF NOT EXISTS dbg_grant_application (
    id                  BIGINT       NOT NULL,
    tenant_id           VARCHAR(20)  DEFAULT '000000',
    flow_instance_id    BIGINT,
    applicant_id        BIGINT       NOT NULL,
    approver_id         BIGINT,
    subject_type        VARCHAR(20)  NOT NULL,
    subject_id          BIGINT       NOT NULL,
    resource_id         BIGINT       NOT NULL,
    action              VARCHAR(40)  NOT NULL,
    effect              VARCHAR(10)  NOT NULL,
    conditions          JSONB,
    effective_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    reason              VARCHAR(500),
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    grant_id            BIGINT,
    create_dept         BIGINT,
    create_by           BIGINT,
    create_time         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by           BIGINT,
    update_time         TIMESTAMPTZ,
    del_flag            CHAR(1)      DEFAULT '0',
    CONSTRAINT pk_dbg_grant_application PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_dbg_app_applicant ON dbg_grant_application (applicant_id) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_dbg_app_status ON dbg_grant_application (status) WHERE del_flag = '0';
CREATE INDEX IF NOT EXISTS idx_dbg_app_flow ON dbg_grant_application (flow_instance_id) WHERE flow_instance_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_dbg_app_resource ON dbg_grant_application (resource_id, action) WHERE del_flag = '0';

COMMENT ON TABLE dbg_grant_application IS '查询权限申请单（M2-02，WF-001）';
COMMENT ON COLUMN dbg_grant_application.status IS 'PENDING/APPROVED/REJECTED/REVOKED';
COMMENT ON COLUMN dbg_grant_application.grant_id IS '批准后生成的授权 ID（dbg_resource_grant.id）';
