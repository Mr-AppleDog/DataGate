-- =============================================================================
-- V8: M2 资源授权引擎 — 授权表（docs/04 第 4.2 节，AUTH-001~004）
--
-- 1. dbg_resource_grant：外部数据资源授权（Allow/Deny/继承/有效期/策略版本）
--    默认拒绝、显式拒绝优先、细粒度优先；授权创建与撤销由审批流（M2-02）负责。
--
-- 偏差（本切片，已在交付报告中记录）：
-- - action 作为单列（每条授权一个动作）；docs/04 第 4.3 节的独立 dbg_grant_action 多动作表留待后续切片。
-- - 以 revoked_at 时间戳标记撤销，不引入 docs/04 第 4.2 节的 status 状态机（SUSPENDED 等）。
-- - expires_at 可空；生产非空约束由授权创建服务按环境强制（M2-02），本切片引擎按 NULL=永不过期处理。
-- =============================================================================

CREATE TABLE dbg_resource_grant (
    id              int8          NOT NULL,
    tenant_id       varchar(20)   NOT NULL DEFAULT '000000',
    subject_type    varchar(16)   NOT NULL,
    subject_id     int8          NOT NULL,
    resource_id     int8          NOT NULL,
    action          varchar(32)   NOT NULL,
    effect          varchar(8)    NOT NULL,
    conditions      jsonb         NULL,
    effective_at    timestamptz   NULL,
    expires_at      timestamptz   NULL,
    revoked_at      timestamptz   NULL,
    source_type     varchar(16)   NOT NULL DEFAULT 'MANUAL',
    source_id       int8          NULL,
    reason          varchar(1000) NULL,
    policy_version  int8          NOT NULL DEFAULT 0,
    create_dept     int8          NULL,
    create_by       int8          NULL,
    create_time     timestamptz   NULL,
    update_by       int8          NULL,
    update_time     timestamptz   NULL,
    del_flag        char(1)       NOT NULL DEFAULT '0',
    CONSTRAINT pk_dbg_resource_grant PRIMARY KEY (id)
);

COMMENT ON TABLE  dbg_resource_grant IS '外部数据资源授权（AUTH-001~004）：默认拒绝、显式拒绝优先';
COMMENT ON COLUMN dbg_resource_grant.subject_type IS 'USER/DEPT/GROUP（docs/03 第 5.2 节）';
COMMENT ON COLUMN dbg_resource_grant.resource_id IS '授权始终引用不可变 resource_id（docs/03 第 3.1 节）';
COMMENT ON COLUMN dbg_resource_grant.action IS '资源动作（docs/03 第 4 节；动作不自动包含其他动作）';
COMMENT ON COLUMN dbg_resource_grant.effect IS 'ALLOW/DENY（显式拒绝优先）';
COMMENT ON COLUMN dbg_resource_grant.conditions IS '标准条件结构（docs/03 第 6 节，不含秘密）';
COMMENT ON COLUMN dbg_resource_grant.effective_at IS '生效时间（null=已生效）';
COMMENT ON COLUMN dbg_resource_grant.expires_at IS '截止时间（null=永不过期；生产非空由创建服务按环境强制）';
COMMENT ON COLUMN dbg_resource_grant.revoked_at IS '撤销时间（null=未撤销）';
COMMENT ON COLUMN dbg_resource_grant.source_type IS 'MANUAL/REQUEST/SYSTEM/EMERGENCY（docs/03 第 2.2 节）';
COMMENT ON COLUMN dbg_resource_grant.source_id IS '工单/来源对象 ID（审批回调幂等）';
COMMENT ON COLUMN dbg_resource_grant.policy_version IS '策略版本快照（缓存键含此版本，docs/03 第 8 节）';
COMMENT ON COLUMN dbg_resource_grant.del_flag IS '逻辑删除（授权撤销不逻辑删除，写 revoked_at）';

-- 关键索引（docs/03 第 7.2 节候选授权查询）
CREATE INDEX idx_dbg_grant_resource_action ON dbg_resource_grant (resource_id, action) WHERE del_flag = '0';
CREATE INDEX idx_dbg_grant_subject ON dbg_resource_grant (subject_type, subject_id, action) WHERE del_flag = '0';
CREATE INDEX idx_dbg_grant_policy_version ON dbg_resource_grant (policy_version);
CREATE INDEX idx_dbg_grant_effect ON dbg_resource_grant (effect) WHERE del_flag = '0' AND revoked_at IS NULL;

-- 并发最后防线：同一审批来源（source_id）不得重复创建同一 (资源,动作,主体) 授权（docs/03 第 11 节幂等）
CREATE UNIQUE INDEX uk_dbg_grant_idempotent
    ON dbg_resource_grant (source_type, source_id, resource_id, action, subject_type, subject_id)
    WHERE source_id IS NOT NULL AND del_flag = '0';
