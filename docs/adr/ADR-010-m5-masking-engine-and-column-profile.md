# ADR-010：M5 字段级脱敏引擎落位与列策略持久化

- 状态：已确认（M5 全部切片 05/01/02/03/04）
- 日期：2026-08-27
- 关联文档：docs/04 §3.7（dbg_column_profile）；docs/06 §11（结果集脱敏）；docs/10 M5-05；docs/02 §7.1（FIELD_MASKING/COLUMN_LINEAGE 能力）；docs/03 §7.4（maskingLevel）

## 背景

M2 查询链路已在鉴权层计算 `MaskingLevel`（AggregatedDecision.masking，prod 默认 MASKED），但：

1. 无脱敏算法实现——`MysqlQueryExecutor.buildRow` 直接吐原值，敏感字段原值可达前端，违反 AGENTS §6 红线"前端脱敏替代服务端流式脱敏"；
2. `dbg_column_profile`（docs/04 §3.7）表未建，无列敏感等级/脱敏类型元数据；
3. docs/10 M5-05 要求"查询预览、普通结果与导出使用同一服务端脱敏引擎"，需一个跨执行器/导出器共用的纯算法引擎；
4. docs/06 §11 要求"无法可靠判断来源的表达式，在生产环境按最高敏感等级处理"，接入时需列来源追踪。

## 决策

1. **脱敏引擎置于 ruoyi-db-core（纯算法 + 安全值对象）**：
   - `MaskingType`（PHONE/ID_CARD/BANK_CARD/EMAIL/ADDRESS/CUSTOM/NONE）、`SensitivityLevel`（PUBLIC/INTERNAL/SENSITIVE/RESTRICTED，含 moreRestrictive）、`MaskingConfig`（自定义 keepPrefix/keepSuffix/maskChar）、`ColumnMaskingPolicy`（静态策略快照）置于 core domain；
   - `FieldMaskingEngine` SPI + `DefaultFieldMaskingEngine` 置于 core `masking` 包，纯 record/enum，无持久化/Web 依赖，连接器（仅依赖 core）可直接复用；
   - 失败关闭：任何掩码异常降级为保留长度等长全掩码，绝不泄露原值；短值（prefix+suffix>=len）全掩码，不保留可识别前缀。

2. **dbg_column_profile 表（V14 迁移）**：resource_id 即 dbg_resource.id（type=COLUMN），1:1 关联，IdType.INPUT；sensitivity_level/masking_type/masking_config(classification_source=MANUAL/RULE/IMPORT)；MANUAL 行不被元数据重同步覆盖（ColumnProfileConverter.shouldPreserveManual）。

3. **ColumnMaskingPolicyResolver SPI**（core）+ `DbColumnMaskingPolicyResolver`（resource 实现，按 COLUMN 资源 ID 批量查 dbg_column_profile 转 ColumnMaskingPolicy），供查询/导出执行器消费；运行时授权级别由鉴权提供，不由解析器提供。

4. **列策略管理 API**：`DbColumnProfileController`（GET /{id}、GET /list-by-table/{tableId}、PUT /{id} 人工 MANUAL 标签，SaCheckPermission db:column:query/mask）+ `ColumnProfileServiceImpl`（setManualLabel 校验目标资源为 COLUMN、applyRuleLabels 跳过 MANUAL 行）。

## 接入查询链路（M5-05c，已落地）

引擎与策略已接入 MySQL/PG 执行路径，查询结果服务端流式脱敏已生效（MaskingApplier 按 JDBC 基列名 lineage，未知来源 prod→HIDDEN）：

- **列来源追踪用 JDBC 元数据**：MySQL/PG 执行器通过 `ResultSetMetaData.getTableName(i)/getColumnName(i)`（基列名，非别名）定位结果列来源表+列，查列策略；`SELECT phone AS x` 的基列名仍为 phone → 命中策略 → 掩码（不泄露）；真实表达式（CONCAT/算术）基列名非真实列 → prod 按 RESTRICTED→HIDDEN（docs/06 §11"无法可靠判断来源按最高等级"）。
- **ExecutionPlan 扩展**（非 ADR-007 冻结对象，可扩展）：新增 `maskingLevel` + 按"规范化表.列"键的列策略快照 Map；网关预解析引用表 COLUMN 子资源 + 策略传入；执行器在 buildRow 应用 FieldMaskingEngine.maskRow，保持 ADR-007 冻结的 QueryExecutor/RowCallback 签名不变。
- **COLUMN_UNMASK / VIEW_PLAINTEXT**：复用 ruoyi-db-auth GrantAdminService 临时授权（source=REQUEST/EMERGENCY，valid_until 短期），conditions.requireRecentReauth=5min 二次认证；到期/撤权立即 policyVersion++ 失效缓存（M2 已有广播机制）。导出执行器同样消费同一引擎（docs/10 M5-05）。

## 影响

- M5-05a/05b 已交付：32 单测全绿（脱敏引擎 21 + 列策略转换 11），全量 44 模块编译 BUILD SUCCESS。
- M5-05c 已接入：查询结果服务端流式脱敏生效，生产可开放普通查询（验收 #13 脱敏侧闭环）。
- M5-01 导出执行器必须复用同一 FieldMaskingEngine（不得各自实现脱敏）。

## 风险

- 接入若改用"结果列名/别名"匹配策略而非 JDBC 基列名，`SELECT sensitive AS x` 会绕过脱敏（安全漏洞）——已明确禁止，必须用基列名来源追踪。
- db:column:query/mask 菜单权限 V18 已 seed，控制器运行期不再 403。

---

## M5-01 导出工单（已落地）

1. 独立 EXPORT 权限 + 两级审批 WarmFlow（V15 dbg_export_approval：apply→owner_approve→dba_approve，PASS 锁定办理人，申请人不能自批）。
2. SQL 密文锁定复用 EncryptedObjectStore：创建时重新解析+鉴权 EXPORT 后将 SQL 存为加密对象，执行前解密；不用隐藏参数替换 SQL（docs/06 §12）。
3. 流式导出复用脱敏：ExportExecutionGatewayImpl 经连接器 queryExecutor 流式执行（plan 驱动 buildRow 脱敏，与查询同引擎），CsvExportRowCallback 应用 CsvInjectionSanitizer 写加密对象（临时文件避免大内存）。
4. 加密对象 24h + 一次性票据：LocalEncryptedObjectStore 信封 AES-256-GCM（DEK 由 KEK 包裹，AAD 绑定 objectKey 防搬移）；下载票据 sha256、5min、单次失效；24h 惰性到期删除。
5. 执行前重鉴权：ExportApprovalCallbackServiceImpl.onApproval 重新决定 EXPORT（权限撤销则失败）。
6. 偏差：导出存储 P0 本地加密对象，MinIO/S3 为后续替换（SPI 不变）；即时通知/到期清理 P0 惰性 + best-effort 日志，通道投递由 alert 模块接通。

## M5-02 SQL 变更工单（已落地）

1. 不可变快照 + 两级审批（V16 dbg_change_order/execution + dbg_change_approval：biz_approve→dba_approve）；SQL 密文锁定，改动回 DRAFT 清空审批结论。
2. precheck 风险标签（ChangeRiskAnalyzer 纯分析：无 WHERE UPDATE/DELETE、破坏性 DDL DROP/TRUNCATE、锁 ALTER、全表扫描；仅标签，不做自动优化/回滚）。
3. 专用 CHANGE 凭据独立池（MysqlChangeExecutor：CredentialPurpose.CHANGE 独立 HikariCP，allowMultiQueries 逐语句结果迭代；再解析校验 CHANGE_DML/CHANGE_DDL，拒绝只读经此路径）。
4. 执行窗口 + 幂等：schedule 设执行窗口；execute 幂等键 sha256(userId+orderId+statementHash)，终态不重复（uk 唯一约束兜底并发）；执行前重新鉴权 CHANGE。
5. 逐语句结果：DbChangeExecution 记录 attempt_no/逐语句 status+affectedRows/遮蔽错误；失败即停止不自动回滚（docs/06 §13）。

## M5-03 Redis 变更工单（已落地）

1. 复用 change 框架（DbChangeOrder change_type=REDIS；ChangeExecutionRequest 扩展 redisCommands+authorizedPrefixes 结构化传递，避免 connector-redis 解析 JSON）。
2. 白名单 + 逐 key 前缀鉴权（RedisChangeCommandValidator：SET/DEL/HSET/HDEL/EXPIRE 白名单，禁 EVAL/MULTI/FLUSHDB/CONFIG 等脚本/事务/管理；每 key 须命中授权 KEY_PREFIX_POLICY 前缀，越权整批拒绝；key 遮蔽）。
3. RedisChangeExecutor（ChangeExecutor impl，Lettuce 结构化派发不接受文本拼接；逐命令结果；失败即停止不自动回滚）。
4. ChangeOrderServiceImpl Redis 路由：create 解析命令 JSON + resolveRedisPrefixes 按 key 命中 KEY_PREFIX_POLICY；execute 填充 redisCommands/authorizedPrefixes + REDIS_WRITE 重鉴权。

## M5-04 紧急访问（已落地）

1. 双人审批（V17 dbg_emergency_access + dbg_emergency_approval：apply→approve1→approve2，两名审批人须不同且均非申请人，服务端强制）。
2. 2h 临时授权 + TOTP + 事件编号：EmergencyApprovalCallbackServiceImpl.onApproval 生成 EMERGENCY Grant（valid_until=now+2h，conditions requireMfa+requireRecentReauth 5min 二次认证）；不续期。
3. 自动到期 + 即时失效：grant validUntil 由鉴权拒绝；revoke 即时 revokeGrant + policyVersion 广播 60s 全局生效；惰性到期标记 EXPIRED。
4. 即时通知 + 事后 24h 复盘：开通/到期/撤销审计；postMortemDueAt=now+24h，复盘内容必填（逾期仍可补但标记）。
5. 偏差：即时通知 P0 best-effort 日志+审计，钉钉/邮件通道由 alert 模块接通；自动到期清理 P0 惰性，定时扫描由 SnailJob 接通。

## 菜单权限迁移（V18 已落地）

V18 种子 sys_menu：导出管理 9250（db:export:apply/approve/download/query）、变更工单 9260（db:change:apply/approve/execute/query）、紧急访问 9270（db:emergency:apply/approve/revoke/query）、列脱敏标签 9280（db:column:query/mask），授权超管 role 1。SaCheckPermission 与 perms 一一对应。db:* 菜单未 seed 前控制器运行期 403——已随 V18 解除。
