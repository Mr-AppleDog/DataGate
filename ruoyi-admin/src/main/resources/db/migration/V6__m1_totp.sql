-- =============================================================================
-- V6: M1-01 TOTP 双因素认证（IAM-005，docs/08 第 5/6 节）
--
-- dbg_user_totp：每用户一条；密钥用 KEK 直接 AES-256-GCM 加密（AAD 绑定 userId，
-- 搬移到其他用户解密失败）。恢复码只存 SHA-256 哈希。
-- =============================================================================

CREATE TABLE dbg_user_totp (
    user_id         int8         NOT NULL PRIMARY KEY,
    tenant_id       varchar(20)  NOT NULL DEFAULT '000000',
    -- KEK 直接加密的 TOTP 密钥（Base32 明文不出库）
    ciphertext      bytea        NOT NULL,
    nonce           bytea        NOT NULL,
    algorithm       varchar(32)  NOT NULL DEFAULT 'AES-256-GCM',
    key_version     varchar(64)  NOT NULL,
    -- PENDING（已生成未确认）/ACTIVE/DISABLED
    status          varchar(16)  NOT NULL DEFAULT 'PENDING',
    -- 恢复码 SHA-256 哈希数组（jsonb text[]），使用后即移除
    recovery_hashes jsonb        NULL,
    bound_at        timestamptz  NULL,
    last_used_at    timestamptz  NULL,
    -- 上次成功验证的时间步（防重放：同一步内的码不可复用）
    last_step       int8         NULL,
    create_time     timestamptz  NULL,
    update_time     timestamptz  NULL
);
COMMENT ON TABLE dbg_user_totp IS 'TOTP 密钥（IAM-005）：密文只写，恢复码仅存哈希';
