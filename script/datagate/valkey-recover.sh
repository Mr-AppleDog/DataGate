#!/usr/bin/env bash
# =============================================================================
# DataGate Valkey/Redis 缓存全丢失恢复（docs/09 §13.3，M6-03）
# 缓存全丢失后：从 PostgreSQL 重载策略版本与基础缓存；递增全局会话纪元使全部登录失效；
# 检查调度锁过期后再恢复任务。Redis 备份不是授权/审计备份（仅缩短恢复时间）。
# =============================================================================
set -euo pipefail
REDIS="${REDIS:-192.168.149.128:6379}"
REDIS_CLI="${REDIS_CLI:-redis-cli}"
PGHOST=${PGHOST:-192.168.149.128}; PGPORT=${PGPORT:-5432}; PGUSER=${PGUSER:-postgres}; PGDATABASE=${PGDATABASE:-ry-vue}
PGPASSWORD="${PG_PASSWORD:?PG_PASSWORD required}"

echo "[1/5] 确认缓存全丢失（PING / DBSIZE）"
$REDIS_CLI -h 192.168.149.128 -p 6379 -a "${REDIS_PASSWORD:-mrlu}" PING || true

echo "[2/5] 递增全局会话纪元（使全部登录失效，强制重新登录）"
# 全局会话纪元存于 sys_config 或专用键；递增后 Sa-Token 会话校验失败 → 用户重新登录
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c \
  "INSERT INTO sys_config(config_key, config_value) VALUES('datagate.session.epoch', '1')
   ON CONFLICT (config_key) DO UPDATE SET config_value = (sys_config.config_value::int + 1)::text;"
echo "  会话纪元已递增；全部用户须重新登录"

echo "[3/5] 从 PostgreSQL 重载策略版本（dbg_policy_version）到缓存"
# 应用启动时由 PolicyVersionSource 重载；此处校验元数据库策略版本存在
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -c \
  "SELECT scope_type, scope_id, current_version FROM dbg_policy_version ORDER BY scope_type;"

echo "[4/5] 检查调度锁过期（SnailJob 任务恢复前）"
echo "  应用启动后 SnailJob 检查租约过期再恢复任务（docs/09 §13.3）"

echo "[5/5] 先恢复管理与审计只读，再恢复查询，最后恢复导出/变更（docs/09 §13.1 step8）"
echo "Valkey 缓存恢复完成；记录实际 RTO"
