# M5 验收证据（docs/10 §14 场景 #8/#9/#13）

> 基线 1.0.0 · 2026-08-28 · 关联 ADR-010、V14–V18 迁移、M5-05/01/02/03/04 实现

## 1. 组件级自动化证据（已通过，全量 44 模块编译 EXIT=0）

| 场景 | 组件 | 单测（数量） | 证明点 |
|---|---|---|---|
| #13 脱敏 | DefaultFieldMaskingEngine | 21 | PHONE/ID_CARD/BANK_CARD/EMAIL/ADDRESS/CUSTOM 掩码、短值全掩码不泄露、失败关闭全掩码、HIDDEN 占位 |
| #13 脱敏 | MaskingApplier | 8 | JDBC 基列名 lineage：敏感列掩码、非敏感直接引用透传、未知来源 prod→HIDDEN 防 `SELECT sensitive AS x` 借名绕过、COLUMN_UNMASK 临时授权明文、无资源引用查询透传 |
| #13 脱敏 | ColumnProfileConverter | 11 | DbColumnProfile↔ColumnMaskingPolicy 转换、JSON config 解析、MANUAL 标签不被重同步覆盖（shouldPreserveManual RULE/IMPORT/null 三分支） |
| #13 脱敏 | LocalEncryptedObjectStore | 5 | 信封 AES-256-GCM round-trip、AAD 绑定 objectKey 防密文搬移（重命名→解密失败）、删除幂等、空内容 |
| #8 导出 | CsvExportRowCallback | 5 | CSV 公式注入防护（=+-@TAB 前置 '）、RFC4180 引用、CRLF、maxBytes 截断 |
| #8 导出 | CsvInjectionSanitizer | 8 | =+-@ 前置、CR/LF 剥离、null→空、失败关闭 |
| #8 导出 | ExportExecutionGatewayImpl | 6 | 流式导出→CSV→加密对象→结果元数据；非只读/多语句/凭据缺失/存储缺失→FAILED 失败关闭 |
| #9 变更 | ChangeRiskAnalyzer | 10 | 无 WHERE UPDATE/DELETE、破坏性 DDL DROP/TRUNCATE、锁 ALTER、全表扫描标签、严重度聚合 |
| #9 变更 | ChangeExecutionGatewayImpl | 4 | 专用 CHANGE 凭据缺失/执行器缺失/数据源未启用→FAILED；经 changeExecutor 执行 |
| #3 Redis | RedisChangeCommandValidator | 10 | 白名单 SET/DEL/HSET/HDEL/EXPIRE、禁 EVAL/MULTI/FLUSHDB 等脚本/事务/管理、逐 key 前缀鉴权越权拒绝、key 遮蔽不泄露 |
| #13 脱敏 | QueryExecutionGatewayImpl | 9 | 网关：解析→鉴权→ExecutionPlan（含 maskingLevel/columnPolicies/columnUnmaskLevels）→流式；拒绝/失败关闭路径 |
| 路径补全 | PathCompletionTest | 13 | MySQL/PG/Redis 资源路径补全（lineage 锚点） |
| #13 脱敏（真实 VM MySQL） | MysqlMaskingEndToEndIntegrationTest | 4 | 真实 192.168.149.128 MySQL：SELECT phone 服务端掩码 138xxxx5678；SELECT phone AS x 别名不绕过（基列名 lineage）；SELECT id 非敏感透传；SELECT CONCAT(phone) AS c 表达式 prod→HIDDEN（value=null） |
| #9 变更执行（真实 VM MySQL） | MysqlChangeExecutionIntegrationTest | 2 | UPDATE 经专用变更账号执行→SUCCEEDED+affectedRows+数据落地；无 WHERE 风险由 precheck/审批控制（执行器不阻断） |
| #8 导出执行（真实 VM MySQL） | ExportExecutionRealDbIntegrationTest | 1 | SELECT phone 流式导出→加密对象落地→解密 CSV 含脱敏 138xxxx5678 不含原值（流式复用脱敏+CSV+加密对象） |

**合计 M5 新增单测 ≈ 107**（db-core 62 含 masking/risk/csv/redis-validator + executor 37 含 export/change/csv + resource 33 含 column-profile/object-store + redis 77 含现有 + mysql 80 含现有）。

## 2. 端到端验证步骤（#8/#9/#13，需运行 app + VM MySQL/PG/Redis）

> 前置：启动 ruoyi-admin（KEK 文件挂载、Flyway V1–V18 自动执行、VM 数据源已配置 + CHANGE 凭据）。
> VM：192.168.149.128 MySQL 8.4(3306) / PG 18(5432) / Redis 8.2(6379)。

### 场景 #8 导出（docs/10 §14 #8）
1. DBA 在目标表 `users` 的列 `phone` 上人工标注 MANUAL SENSITIVE PHONE（PUT /db/column-profile/{resourceId}，db:column:mask）。
2. 用户 POST /db/export/requests（statement=`SELECT phone FROM users`，ownerApproverId+dbaApproverId）→ 返回 jobId，status=PENDING_APPROVAL。
3. 资源 Owner POST /db/export/jobs/{id}:approve；DBA POST :approve → 触发执行（ExportApprovalCallbackServiceImpl.onApproval 执行前重鉴权 EXPORT + 解密 SQL + 重解析列脱敏 + 流式导出 → SUCCEEDED，objectKey/fileHash/expires_at=now+24h）。
4. 用户 POST /db/export/jobs/{id}:download-ticket → 返回 ticket（5min，单次）。
5. GET /db/export/downloads/{ticket} → 下载 CSV；**断言**：CSV 中 phone 列值为 `138****5678`（脱敏），非原值。
6. 再次 GET /db/export/downloads/{ticket} → **403/410 票据已失效**（一次性，download_count=1，ticket_hash 清空）。
7. 24h 后 GET → 对象已删除（惰性 EXPIRED→DELETED）。

### 场景 #9 SQL 变更（docs/10 §14 #9）
1. 用户 POST /db/change/orders（changeType=DML，statement=`UPDATE users SET name=? WHERE id=1`，bizApproverId+dbaApproverId）→ DRAFT。
2. POST :precheck → PRECHECKED（precheck_result 含 risks/severity；WHERE id → LOW）。
3. POST :submit → PENDING_APPROVAL；业务负责人 :approve；DBA :approve → APPROVED（onApproval）。
4. DBA POST :schedule（executionWindowStart/End）→ SCHEDULED。
5. **SQL 篡改测试**：尝试用新 statement 重新提交 → 拒绝（statement_encrypted 锁定，改动须回 DRAFT 清空审批，docs/05 §4.5）。
6. POST :execute（到窗口）→ RUNNING→SUCCEEDED；**断言**：使用专用 CHANGE 凭据（CredentialPurpose.CHANGE 独立池）执行；DbChangeExecution 记录逐语句 affectedRows；幂等键 sha256(userId+orderId+statementHash) 终态不重复（重复 :execute 返回已有结果，无重复写入）。
7. 无 WHERE 的 UPDATE precheck → HIGH 风险标签（NO_WHERE+FULL_TABLE_SCAN）。

### 场景 #13 脱敏（docs/10 §14 #13）
1. 同 #8 step1 标注 phone 列 SENSITIVE PHONE。
2. 用户查询 `SELECT phone FROM users`（prod maskingLevel=MASKED）→ 结果 phone=`138****5678`（服务端流式脱敏，前端无原值）。
3. `SELECT phone AS x FROM users` → x 列基列名仍 phone → 命中策略 → 掩码（防借名绕过）；`SELECT CONCAT(phone,'') AS c` → 未知来源 prod→HIDDEN（value=null）。
4. 用户申请 COLUMN_UNMASK 临时授权（POST /db/workflow/apply，action=COLUMN_UNMASK，conditions requireRecentReauth=5min，短期 expiresAt）→ 审批通过后查询该列得**原值**（明文）；5min 后二次认证失效→恢复掩码；到期 policyVersion++ 缓存失效→自动回收（M2 广播）。
5. 导出同列 → 同一 FieldMaskingEngine 脱敏（#8 已验）。
6. 无明文权限用户：查询 + 导出均只得脱敏值（步骤 2/#8 step5）。

## 3. 安全红线证据（AGENTS §6）

- ❌→✌️ 服务端流式脱敏：MaskingApplier + MysqlQueryExecutor/PostgresqlQueryExecutor.buildRow 后 applyMasking（plan 驱动），前端无原值。
- ❌→✌️ 导出独立 EXPORT 权限 + 两次鉴权：apply 鉴权 EXPORT + onApproval 执行前重鉴权（权限撤销则失败）。
- ❌→✌️ 一个数据库账号查询/变更/采集分离：QUERY 凭据（查询/导出）vs CHANGE 凭据（变更独立池）vs MONITOR（采集）。
- ❌→✌️ CSV 公式注入防护：CsvInjectionSanitizer 失败关闭。
- ❌→✌️ 审批后 SQL 不可篡改：statement_encrypted 信封锁定 + statement_hash；改动回 DRAFT 清空审批。
- ❌→✌️ Redis 禁脚本/事务/管理：RedisChangeCommandValidator 白名单 + FORBIDDEN 集。
- ❌→✌️ 紧急不续期 + 双人 + TOTP：EMERGENCY Grant valid_until≤2h + requireMfa + 两名不同审批人。
- ❌→✌️ 加密对象搬移检测：LocalEncryptedObjectStore AAD 绑定 objectKey。
- ❌→✌️ 幂等键绑定用户+动作+摘要：change execute sha256(userId+orderId+statementHash)。

## 4. 待执行的真实 DB 端到端（M6 上线前门禁）

#13/#9/#8 执行核心已真实执行（VM MySQL 192.168.149.128:3306 root/mrlu，integration tag）：
- #13 脱敏：MysqlMaskingEndToEndIntegrationTest 4/4（SELECT phone 掩码/别名不绕过/非敏感透传/表达式 HIDDEN）
- #9 变更：MysqlChangeExecutionIntegrationTest 2/2（UPDATE 经专用变更账号执行+数据落地）
- #8 导出：ExportExecutionRealDbIntegrationTest 1/1（流式导出→加密对象→解密 CSV 含脱敏值不含原值）

两级审批+票据+幂等（WarmFlow+服务编排）为组件单测覆盖（ExportJobService/ChangeOrderService 逻辑）+ §2 手动步骤；全栈 Spring 集成（app 启动+grants+WarmFlow 端到端）留 M6。建议 M6 阶段：
- 写 @Tag("integration") 的 M5 端到端测试（gateway + VM MySQL labeled column → 断言掩码；export ticket 一次性；change 幂等）；
- 或按 §2 手动执行并记录结果至本文件。

## 5. 偏差（ADR-010）

- 导出存储 P0 本地加密对象（LocalEncryptedObjectStore），MinIO/S3 后续替换（SPI 不变）。
- 即时通知/自动到期清理 P0 best-effort 日志+惰性，钉钉/邮件通道由 alert 模块、定时扫描由 SnailJob 接通。
- PG 变更执行器 P0 未实现（MysqlChangeExecutor 仅 MySQL；PG 复用 SPI 待加）。
