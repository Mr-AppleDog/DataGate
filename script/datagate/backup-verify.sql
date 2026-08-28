-- =============================================================================
-- DataGate 备份校验 SQL（docs/09 §12.1 step / §13.1 step5，M6-03）
-- 恢复后或定期校验：schema 版本、审计哈希链根、授权记录数、凭据密文存在性、备份规模。
-- =============================================================================
\set ON_ERROR_STOP on

-- 1. Flyway schema 版本（与基线一致，未漂移）
SELECT installed_rank, version, description, success FROM flyway_schema_history
WHERE type='SQL' ORDER BY installed_rank DESC LIMIT 20;

-- 2. 审计哈希链：每分片（UTC 日）首尾事件 + 篡改抽样（应用层 verifyChain 复核）
SELECT chain_key, COUNT(*) AS events, MIN(occurred_at) AS first, MAX(occurred_at) AS last,
       (SELECT event_hash FROM dbg_audit_event e2 WHERE e2.chain_key = e.chain_key ORDER BY occurred_at DESC LIMIT 1) AS latest_hash
FROM dbg_audit_event e
GROUP BY chain_key
ORDER BY chain_key DESC LIMIT 30;
-- 注：完整哈希链重算校验由 IAuditService.verifyChain / verifyChainRange 完成（防篡改）

-- 3. 授权记录数（恢复前后对照）
SELECT effect, status, COUNT(*) FROM dbg_resource_grant WHERE del_flag='0' GROUP BY effect, status;

-- 4. 凭据密文存在性（无 KEK 不可恢复，但密文应在）
SELECT COUNT(*) AS credential_versions,
       COUNT(*) FILTER (WHERE ciphertext IS NOT NULL) AS with_ciphertext
FROM dbg_credential_version;

-- 5. 备份规模（行数/大小抽样）
SELECT 'audit' tbl, COUNT(*) FROM dbg_audit_event
UNION ALL SELECT 'grant', COUNT(*) FROM dbg_resource_grant
UNION ALL SELECT 'resource', COUNT(*) FROM dbg_resource
UNION ALL SELECT 'datasource', COUNT(*) FROM dbg_data_source WHERE del_flag='0';

-- 6. 预置环境风险级校验（生产必须 CRITICAL）
SELECT code, risk_level FROM dbg_environment WHERE del_flag='0' AND code='prod';
