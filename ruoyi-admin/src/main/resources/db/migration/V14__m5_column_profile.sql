-- =============================================================================
-- V14: M5 列敏感策略与脱敏（docs/04 §3.7 dbg_column_profile，MASK-001）
--
-- 每个 COLUMN 资源绑定敏感等级与脱敏类型；元数据重新同步不覆盖人工确认（MANUAL）标签。
-- resource_id 即 dbg_resource.id（type=COLUMN），不另设自增主键，1:1 关联。
-- =============================================================================
CREATE TABLE dbg_column_profile (
    resource_id           int8         NOT NULL,
    sensitivity_level     varchar(16)  NOT NULL DEFAULT 'PUBLIC',
    masking_type          varchar(32)  NOT NULL DEFAULT 'NONE',
    masking_config        jsonb        NULL,
    classification_source varchar(16)  NOT NULL DEFAULT 'RULE',
    confirmed_by          int8         NULL,
    confirmed_at          timestamptz  NULL,
    CONSTRAINT pk_dbg_column_profile PRIMARY KEY (resource_id)
);
COMMENT ON TABLE  dbg_column_profile IS '列敏感策略（docs/04 §3.7）：resource_id 必须为 COLUMN 资源；元数据重同步不覆盖 MANUAL';
COMMENT ON COLUMN dbg_column_profile.sensitivity_level      IS 'PUBLIC/INTERNAL/SENSITIVE/RESTRICTED';
COMMENT ON COLUMN dbg_column_profile.masking_type           IS 'PHONE/ID_CARD/BANK_CARD/EMAIL/ADDRESS/CUSTOM/NONE';
COMMENT ON COLUMN dbg_column_profile.masking_config         IS '自定义掩码配置 JSON（keepPrefix/keepSuffix/maskChar）';
COMMENT ON COLUMN dbg_column_profile.classification_source IS 'MANUAL/RULE/IMPORT；MANUAL 不被重同步覆盖';
COMMENT ON COLUMN dbg_column_profile.confirmed_by           IS '人工确认人（MANUAL 时必填）';
CREATE INDEX idx_dbg_col_profile_level ON dbg_column_profile (sensitivity_level) WHERE sensitivity_level <> 'PUBLIC';
