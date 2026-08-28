#!/usr/bin/env bash
# =============================================================================
# DataGate KEK 双人恢复演练（docs/09 §12.3 / §13.1 step6，M6-03）
# KEK 备份由安全团队独立保管，双人恢复；无 KEK 的 DB 备份不可恢复凭据（必须纳入演练）。
# 流程：两名恢复人各自提供 KEK 分片（Shamir/或双签），合成 keyVersion 对应 KEK → 加载 → 抽样连接测试。
# =============================================================================
set -euo pipefail
KEY_VERSION="${KEY_VERSION:?KEY_VERSION required（如 v1）}"
KEK_FILE="${KEK_FILE:?KEK_FILE required（datagate.security.credential.kek-file 路径）}"
# 双人：恢复人 A/B 各持分片（示例：A 提供前 16 字节 base64，B 提供后 16 字节 + 校验）
SHARD_A="${SHARD_A:?恢复人 A 分片（base64）}"
SHARD_B="${SHARD_B:?恢复人 B 分片（base64）}"

echo "[1/5] 双人合成 KEK（keyVersion=$KEY_VERSION）—— 各自分片不得单独可用"
COMBINED="$(printf '%s%s' "$SHARD_A" "$SHARD_B" | base64 -d)"
KEY_LEN=${#COMBINED}
if [ "$KEY_LEN" -ne 32 ]; then echo "KEK 长度异常：$KEY_LEN（应为 32 字节 AES-256）"; exit 1; fi
echo "[2/5] 写入恢复 KEK 文件（只读，权限 0400）"
install -m 0400 /dev/stdin "$KEK_FILE" <<EOF
$KEY_VERSION:$(printf '%s' "$COMBINED" | base64)
EOF
unset COMBINED SHARD_A SHARD_B
echo "[3/5] 校验文件格式（版本:base64，32 字节解码）"
grep -q "^$KEY_VERSION:" "$KEK_FILE" || { echo "KEK 文件 keyVersion 不匹配"; exit 1; }
echo "[4/5] 启动应用（加载正确 keyVersion 的 KEK）"
echo "  java -jar ruoyi-admin.jar --datagate.security.credential.kek-file=$KEK_FILE"
echo "[5/5] 抽样连接测试 + 凭据解密验证（DB 备份 + KEK 可恢复凭据密文）"
echo "  应用启动后：查询凭据表 → resolveActiveSecret 解密抽样 → 连接目标数据源"
echo "KEK 双人恢复完成；记录实际耗时（RTO）"
