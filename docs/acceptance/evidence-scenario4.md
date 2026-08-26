# 场景4 证据：审计不可变性与篡改检测（AUD-004 / docs/08 §9.3）

日期：2026-08-26（UTC 分片 chain_key=20260826）
工具：`target-acceptance/export-audit.ps1`（导出分片）+ `target-acceptance/audit-verify.mjs`（按 AuditHashChain.java 公式独立重算）

## 哈希链公式（与 AuditHashChain.java 逐行对齐）

```
eventHash = SHA-256( previousHash|eventId|category|action|actorId|canon(actorSnapshot)|
                     targetType|targetId|canon(targetSnapshot)|result|sourceIp|traceId|
                     canon(details)|occurredAt )
canon(map)  = "{k=v;k=v;}" 键排序、嵌套递归；canon(list)="[v,v,]"；null→""
occurredAt  = Instant.toString()（微秒截断，0/3/6 位小数）
分片首事件 previousHash = "GENESIS"
```

注意：`user_agent` 列不在哈希覆盖范围内（实现选择，见偏差记录）。

## 基线：26 个事件链完整

```
total=26, intact=true, brokenAtId=null
```

事件覆盖：DATASOURCE_SSRF_BLOCKED×3 / DATASOURCE_CREATE / CREDENTIAL_CREATE /
DATASOURCE_VERIFY×2 / METADATA_SYNC×2 / TOTP_SETUP×2 / TOTP_BIND×2 /
TOTP_LOGIN_REJECTED×8 / TOTP_RECOVERY_USED×2 / TOTP_UNBIND_ADMIN / LOGIN_BLOCKED×2

## 测试1：普通 UPDATE 被数据库层拒绝 ✅

```
update dbg_audit_event set result='SUCCESS' where id=2092585729867243521;
→ ERROR: dbg_audit_event is append-only (AUD-004)
  CONTEXT: PL/pgSQL function dbg_audit_immutable() line 3 at RAISE
```

## 测试2：普通 DELETE 被数据库层拒绝 ✅

```
delete from dbg_audit_event where id=2092585729867243521;
→ ERROR: dbg_audit_event is append-only (AUD-004)
```

## 测试3：特权篡改（模拟 DBA 禁用触发器）→ 可检测 ✅

```
alter table dbg_audit_event disable trigger trg_dbg_audit_event_immutable;
update dbg_audit_event set result='SUCCESS'
  where id=2092585729867243521;   -- 原值 DENIED（TOTP_LOGIN_REJECTED），典型"洗白失败登录"攻击
alter table dbg_audit_event enable trigger trg_dbg_audit_event_immutable;
```

链校验结果：
```
intact=false, brokenAtId=2092585729867243521, linkOk=true, hashOk=false
```
重算哈希与存储哈希不符 → 精确定位到被篡改行。

## 测试4：特权删除（禁用触发器后删行）→ 可检测 ✅

```
（同法禁用触发器）delete from dbg_audit_event where id=2092585729867243521;
```

链校验结果：
```
total 26→25, intact=false, brokenAtId=2092585730320228353（被删行的后继）, linkOk=false
```
后继行的 previous_hash 指向已消失的哈希 → 断链暴露删除行为。

## 测试5：恢复后链复验完整 ✅

被篡改字段改回原值 / 被删行按备份插回（occurred_at 分区键不变）后：
```
total=26, intact=true, brokenAtId=null
```

## 结论

- 应用层无任何 update/delete 入口（IAuditService 只有 append/verifyChain）✅
- 数据库层触发器强制 append-only ✅
- 即使 DBA 级特权篡改/删除，哈希链重算可定位到具体事件 ✅
- 审计故障失败关闭：append 声明 rollbackFor=Exception 向上传播（静态项 4 复核）

## 过程中的操作事故与处置（如实记录）

恢复删除行时首版脚本在 INSERT 失败后中止（ON_ERROR_STOP），导致 7 个分区的
immutable 触发器短暂处于 disabled 状态（约 1 分钟）。已立即 re-enable 并复核
pg_trigger 全部 tgenabled=O。该窗口内无审计写入被篡改（链复验 26/26 通过）。
教训已转化为脚本修正（psql-exec.ps1 输出净化、备份用 -t -A）。
