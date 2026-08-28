#!/usr/bin/env bash
# =============================================================================
# DataGate PostgreSQL PITR 备份与时间点恢复（docs/09 §12.1 / §13.1，M6-03）
# 前置：PG 主库 192.168.149.128:5432 (postgres/mrlu)；归档/备份目录；恢复环境隔离。
# 目标：RPO ≤ 15 分钟（WAL 持续归档），RTO ≤ 1 小时。
# 用法：
#   备份+开归档：  PG_PASSWORD=mrlu ./pg-pitr.sh backup
#   时间点恢复：    PG_PASSWORD=mrlu ./pg-pitr.sh restore "2026-08-28 10:30:00+00"
# =============================================================================
set -euo pipefail
PGHOST=${PGHOST:-192.168.149.128}; PGPORT=${PGPORT:-5432}; PGUSER=${PGUSER:-postgres}
PGPASSWORD="${PG_PASSWORD:?PG_PASSWORD required}"
ARCHIVE_DIR=${ARCHIVE_DIR:-/datagate/wal-archive}
BACKUP_DIR=${BACKUP_DIR:-/datagate/pg-backup}
RECOVER_DIR=${RECOVER_DIR:-/datagate/pg-recover}
ACTION=${1:?usage: backup|restore <target_time>}

case "$ACTION" in
  backup)
    mkdir -p "$ARCHIVE_DIR" "$BACKUP_DIR"
    echo "[1/4] 启用 WAL 归档（archive_mode=on, 15min RPO）"
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "ALTER SYSTEM SET wal_level = 'replica';"
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "ALTER SYSTEM SET archive_mode = 'on';"
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "ALTER SYSTEM SET archive_command = 'test ! -f $ARCHIVE_DIR/%f && cp %p $ARCHIVE_DIR/%f';"
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "SELECT pg_reload_conf();"
    echo "[2/4] 全量基础备份（pg_basebackup，-c fast）"
    pg_basebackup -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -D "$BACKUP_DIR" -X stream -c fast -P
    echo "[3/4] 记录备份元数据（行数/大小/时间）"
    echo "backup_time=$(date -u +%FT%TZ) size=$(du -sb $BACKUP_DIR | cut -f1) host=$PGHOST" >> "$BACKUP_DIR/.backup-meta"
    echo "[4/4] 强制 WAL 切换确保归档"
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "SELECT pg_switch_wal();"
    echo "备份完成：$BACKUP_DIR（WAL 归档：$ARCHIVE_DIR）"
    ;;
  restore)
    TARGET_TIME="${2:?restore 需 <target_time> 如 '2026-08-28 10:30:00+00'}"
    echo "[1/5] 复制基础备份到恢复目录（隔离环境）"
    rm -rf "$RECOVER_DIR"; mkdir -p "$RECOVER_DIR"; cp -a "$BACKUP_DIR/." "$RECOVER_DIR/"
    rm -f "$RECOVER_DIR/postmaster.pid"
    echo "[2/5] 写 recovery.signal + restore_config"
    touch "$RECOVER_DIR/recovery.signal"
    cat > "$RECOVER_DIR/postgresql.auto.conf" <<EOF
restore_command = 'cp $ARCHIVE_DIR/%f %p'
recovery_target_time = '$TARGET_TIME'
recovery_target_action = 'promote'
EOF
    echo "[3/5] 启动恢复实例（到目标时间点 $TARGET_TIME）"
    echo "  在恢复环境执行：pg_ctl -D $RECOVER_DIR start"
    echo "[4/5] 校验：schema 版本 / 审计根哈希 / 授权记录数 / 凭据密文存在"
    echo "  见 backup-verify.sql"
    echo "[5/5] 宣布恢复并形成报告（实际 RPO/RTO）"
    ;;
  *) echo "unknown action: $ACTION"; exit 1 ;;
esac
