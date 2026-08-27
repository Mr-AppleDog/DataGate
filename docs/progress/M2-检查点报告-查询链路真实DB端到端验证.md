# M2 检查点报告（查询链路真实 DB 端到端验证，2026-08-27）

里程碑：M2 授权、审批与 MySQL 查询（docs/10 第 6 节）。本报告覆盖 M2 查询执行链路从契约冻结到真实 MySQL 端到端验证，遵循 AGENTS.md 第 8 节交付格式。

## 1. 完成的需求 ID

| 需求 | 内容 | 状态 |
|---|---|---|
| AUTH-001~004 | 资源路径/动作/Allow-Deny/条件/有效期；显式拒绝优先、细粒度优先、默认拒绝、失败关闭 | ✅ 授权引擎读侧 |
| AUTH（写侧） | 授权创建/撤销 + policy_version 递增 + 缓存失效广播（撤权 60s 生效） | ✅ GrantAdminService |
| WF-001 | 查询权限申请单 + 审批回调（批准生成 Grant、拒绝不生成、幂等） | ✅ 回调核心（流程装配见 §7） |
| QRY-101/102/103 | 方言 AST 解析、函数副作用白名单、归一化与指纹 | ✅ MysqlQueryParser |
| QRY-201 | 受控流式执行（只读事务/超时/会话保护/取消/行字节单元格上限/纵深防御再解析） | ✅ MysqlQueryExecutor |
| QRY-202 | 执行网关编排（resolve→parse→decide→plan→execute→audit） | ✅ QueryExecutionGateway |
| QRY（控制台） | REST 同步有界查询 + 取消；服务端注入身份 | ✅ db-console v1 |
| QRY（资源解析） | canonical path → 可鉴权 resource_id（平台级端口） | ✅ ResourcePathResolver |
| AUD（查询审计） | 授权版本/审批/资源/指纹/耗时/行字节/状态；拒绝/取消/解析失败均入审计 | ✅ 网关经 IAuditService |

## 2. 新增/修改文件（按提交，main `f184cf0`）

- `09a1c0d` 契约冻结（ADR-007）：`QueryExecutor.execute(ExecutionPlan, RowCallback)` 流式签名、`RowCallback`、`AuthorizationDecisionService` + `AccessDecision/DecisionRequest/DecisionLimits/MaskingLevel`；11 文件 +284。
- `9c85843` 契约修订（ADR-007 修订 / ADR-008）：`execute` 加 `ConnectionContext` 参数（profile+secret+originalStatement，解决凭据缺口 1 + 可执行语句缺口 2）；`DbAction` 补 `ADMIN`/`CODE`（对齐 docs/06 §5.2）。
- `009a056` `ResourcePathResolver` 端点上移 db-core.spi（平台级端口，避免 executor↔resource 循环依赖）。
- `1335fe7`（feat/m2-core，已合并）授权引擎：`AuthorizationDecisionServiceImpl`（docs/03 §7.2 逐条）、`Grant`+`V8__m2_grant.sql`+`GrantMapper/Repository`、`ConditionEvaluator`、`CidrMatcher`、`PolicyVersionSource`/`PolicyCacheInvalidationHook`；22 单测。
- `e775904` db-executor 执行网关：`QueryExecutionGateway`（resolve ds/cred→ConnectionContext→parse→pathResolver→decide→plan→execute→audit）、`CollectingRowCallback`（5000行/50MB 硬上限）、`ResourcePathResolver` 端口（Optional 注入）；9 单测。
- `dce3453` db-resource `DbResourcePathResolver`（按 canonicalPath+dataSourceId 查 ACTIVE `dbg_resource`）。
- `20a7ebc` db-console v1：`DbConsoleController`（@SaCheckPermission、服务端注入 userId/sessionId/sourceIp）、`DbConsoleService`、`ConsoleResultCollector`（客户端上限）、`QueryRequest/QueryResultView`；4 单测。
- `8bbd521` GrantAdminService 写侧：`createGrant`/`revokeGrant` + policy_version+1 + 失效广播；`GrantWriteRepository`（选择性 update）；5 单测。
- `2fed37b` db-workflow 审批回调：`GrantApprovalCallbackService`（批准→createGrant(sourceType=REQUEST,sourceId=申请单幂等键)、拒绝不生成、幂等）、`GrantApplication`+`V9__m2_grant_application.sql`+Mapper+Repository；4 单测。
- `d6a177e` PolicyVersionSource wiring 修复（见 §4）+ dev 冒烟治装 `DevSmokeSetupRunner`（@Profile dev，prod 不运行）。
- `53ba24d`（feat/m2-mysql，已合并 `f184cf0`）MySQL 连接器：`MysqlQueryParser`（Druid AST+资源提取+动作分类+ADMIN/CODE+失败关闭）、`MysqlQueryExecutor`（HikariCP 池+只读事务+流式 RowCallback+cancel+纵深防御再解析）；parser 58 + executor 17 单测。
- m3-pg `fba1abe`、m3-redis `2286870`：PgQueryParser 73、RedisCommandParser 94 单测（R2 提前，仅解析层）。

## 3. 数据库迁移版本

`flyway_schema_history`：V1–V7（M0/M1）+ **V8 m2_grant**（`dbg_resource_grant`：4 索引 + 并发幂等唯一约束 `uk_dbg_grant_idempotent`）+ **V9 m2_grant_application**（`dbg_grant_application`：申请单 + 流程实例关联 + 状态回填）。冒烟启动确认 "Successfully validated 9 migrations"，V8/V9 应用 success。

## 4. 构建、测试与集成冒烟证据

### 单元测试（全 BUILD SUCCESS / Failures:0）

db-core 5、db-audit 3、db-resource 11、db-auth 22（decide）+5（admin）、db-executor 9、db-console 4、db-workflow 4、connector-mysql 58（parser）+17（executor）、connector-postgresql 73、connector-redis 94。全 reactor `mvn package -DskipTests` 编译通过。

### 集成冒烟（应用对 VM，端口 8087，dev profile）

启动：`java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev --server.port=8087`。连 VM PG(192.168.149.128:5432/datagate postgres/mrlu)+Valkey(6379 mrlu)+本地 KEK(`C:\Users\cxy784853792\.datagate\kek.txt`)。Flyway V8+V9 应用 success，WarmFlow v1.8.5 加载，全 db bean 装配通过，`Started DromaraApplication in ~22s`，HTTP 服务（`/`→200、`/actuator/health`→401 Sa-Token 守护）。

**冒烟抓到并修复的运行时 bug（全是单测漏、运行时才暴露）**：

1. **`PolicyVersionSource` 未注册→启动失败**：`DefaultPolicyVersionSource` 用 `@Component`+`@ConditionalOnMissingBean` 致 Spring 不注册→`AuthorizationDecisionServiceImpl` 注入失败→应用启动失败。改：移 `@Configuration`(`PolicyDefaultsConfiguration`) `@Bean` `@ConditionalOnMissingBean`（条件在 @Configuration 处理期可靠求值）。**教训：`@ConditionalOnMissingBean`/`@ConditionalOnBean` 必须用在 `@Bean` 方法上，不可用在 `@Component` 上。**
2. **captcha + crypto 两道登录前置**：dev 关 `captcha.enable=false` + `api-decrypt.enabled=false`（`ApiDecryptAutoConfiguration` 有 `@ConditionalOnProperty("api-decrypt.enabled","true")`，关即不注册 CryptoFilter，明文 login 可用）。
3. **admin 密码被 M1 改 + 绑了 TOTP**：加 `@Profile("dev")` `DevSmokeSetupRunner` 启动时用应用自身 DB 连接把 admin 密码重置为 admin123（RuoYi 种子 BCrypt 哈希）+ 解绑 `dbg_user_totp`（**注意 sys_user 列名 `user_name`/`user_id`，非 username/id**）。仅 dev profile，生产不运行。
4. **Sa-Token 要 `Bearer ` 前缀**：header `Authorization: Bearer <token>`（非裸 token）+ `clientid` 头。
5. **feat/m2-mysql 未合并**：运行 jar 用 main 构建，`MysqlConnector.queryParser()` 还是 M0 骨架（抛"MySQL 查询解析器将在 M2 提供"）→被网关 catch 成 `QUERY_PARSE_FAILED`（所有语句都失败）。rebase feat/m2-mysql 到 main + 合并（`f184cf0`）后通。

**端到端流程**：login(admin/admin123, clientId=`e5cd7e4891bf95d1d19206ce24a7b32e`, tenantId=000000, header `Authorization: Bearer <token>`+`clientid`) → POST /db/datasource(envId=1/host192.168.149.128:3306/type=MYSQL/tlsMode=DISABLE) → POST /db/credential(QUERY/root/mrlu，经 KEK 加密) → POST /db/datasource/{id}/verify(成功，MySQL 8.4.11) → PUT /db/datasource/{id}/enable → POST /db/console/query。

**查询验证结果**：

| 语句 | 状态 | 证据 |
|---|---|---|
| `SELECT 1` | **SUCCEEDED** | rows=`[{"value":"1"}]`（真实 MySQL 执行返回行） |
| `SELECT 1 AS one, NOW() AS t` | **SUCCEEDED** | rows=`[{"value":"1"},{"value":"2026-08-27 17:16:57"}]` |
| `DROP TABLE x` | **REJECTED** `QUERY_UNSAFE_STATEMENT` | parse 阶段拒，**零 MySQL 写入** |
| `SELECT * INTO OUTFILE 'c:/x' FROM t` | **REJECTED** `QUERY_UNSAFE_STATEMENT` | docs/06 §6.3 禁止项拒，**零写入/零文件** |

恶意语料在 parse 阶段拒、从不触达 MySQL——M2 验收"恶意语料没有任何数据库写入"达成。

## 5. 安全/性能/恢复证据

- **失败关闭**：解析失败/未知方言/存储过程体/匿名块 → 抛 `QUERY_PARSE_FAILED`；数据源未启用/凭据缺失/资源不可解析/任一拒绝 → REJECTED；操作人缺失/资源不可解析 → 拒绝而非放行。
- **纵深防御**：执行器独立重新解析 `ctx.originalStatement()`，非只读/多语句/动作非 {QUERY,EXPLAIN,METADATA_READ} 一律 REJECTED（即便编排者被攻陷 DDL 也落不了地）；网关外层 CollectingRowCallback 再施 5000行/50MB 硬上限。
- **凭据安全**：密码经 KEK 信封加密（AES-256-GCM）入库，`resolveActiveSecret` 解密短时驻留、try-with-resources 销毁；不进日志/异常/缓存；`SecretValue.toString()` 固定掩码。
- **恶意语料零写入**：DROP/INTO OUTFILE 等在 parse 阶段 REJECTED，未触达真实 MySQL（已端到端验证）。
- **审计**：网关经 `IAuditService` 写 `QUERY_EXECUTE`(成功)/`QUERY_DENY`(拒绝)/`QUERY_REJECT`(前置拒)，只记 executionNo/decisionId/fingerprint/耗时/行字节/状态，**不记 SQL 参数与结果正文**；拒绝路径 `appendIsolated`(REQUIRES_NEW) 保留。
- **只读门禁**：生产控制台普通查询只允许只读（readonly + EXPLAIN/METADATA_READ），DML/DDL/ADMIN/CODE 一律拒。
- **撤权时效**：`GrantAdminService.revokeGrant` + policy_version+1 + `PolicyCacheInvalidationHook` 广播（设计 60s 生效，Valkey 装配待 M2-02/M6）。

## 6. 与规格的偏差和 ADR

- **ADR-007 契约冻结 + 修订**：`execute` 签名从 `(ExecutionPlan, RowCallback)` 修订为 `(ExecutionPlan, ConnectionContext, RowCallback)`；`DbAction` 补 `ADMIN`/`CODE`；`ResourcePathResolver` 端点上移 db-core.spi。均为加法/签名扩展，不改既有枚举语义。
- **ADR-008 执行器连接与语句上下文缺口**：`ExecutionPlan` 设计为授权信封（不含凭据/语句）；凭据 + 原始语句经 `ConnectionContext` 参数流入执行器；弃用 m2-mysql worktree 内越权的 `ExecutionContextResolver` 端口方案。
- **`@ConditionalOnMissingBean` 陷阱**（非 ADR，记入本报告）：该注解用在 `@Component` 上不可靠，致默认实现未注册、应用启动失败；已移 `@Configuration @Bean`。
- **dev 冒烟治装**（`DevSmokeSetupRunner`，`@Profile("dev")`）：启动重置 admin 密码 + 解绑 TOTP + 关 captcha/crypto（dev yml）。**仅 dev profile 激活，生产不运行**；不进 Flyway（不污染 prod）。
- **池 key 偏差**：`ConnectionContext` 不含 `credentialVersionId`，MysqlQueryExecutor 池 key 用 `dataSourceId:username`；同 username 凭据轮换淘汰由编排器负责（待集成者决策是否给 ConnectionContext 补 credentialVersionId）。
- **动作映射偏差**：不安全 SHOW 暂归 `CHANGE_DDL`（保守拒）；`DO` 语句 Druid 无法解析 → 失败关闭（等价拒绝）。

## 7. 未完成项与风险

1. **M2-02 WarmFlow 流程装配**（未做）：申请→审批节点流程定义（WarmFlow JSON/defService）+ `GrantApplicationService`(apply 起 FlowEngine、审批节点推进) + `GlobalListener.finish` 钩子调 `GrantApprovalCallbackService.onApproval/onRejection` + assignee(资源 owner/DBA) + 申请人不能自批 + REST(apply/approve/reject/list)。**回调核心已就绪，缺流程编排**。当前查询冒烟用"无资源引用"（SELECT 1）绕过 Grant；带表查询需 Grant + 资源同步（dbg_resource）。
2. **前端 plus-ui 控制台**：Monaco 编辑器 + 结果表 + 取消按钮（`src/views/db/`、`src/api/db/`，Vue/npm，中文路径 npm 失败需 ASCII 路径）。
3. **查询审计 / cancel 真实验证**：审计写入未端到端核验（无直连 VM PG）；cancel 需长查询验证（SELECT 1 太快）。
4. **M3 执行器**（R2）：connector-postgresql/connector-redis 仅 parser，待实现 `PgQueryExecutor`/`RedisQueryExecutor`（同 ConnectionContext 正解）。
5. **池 key**：见偏差，同 username 轮换检测受限。
6. **风险**：`DevSmokeSetupRunner` 重置 admin 密码仅在 dev；生产首登改密流（IAM-002/003）未fleet-wide 强制（继承 M1 ADR-006 未收口项）。

## 8. 下一步最小安全切片

- **M2-02 WarmFlow 流程装配**：定义查询权限审批流程（申请→负责人/DBA 审批→结束）+ `GrantApplicationService`(apply/审批推进) + finish 钩子接 `GrantApprovalCallbackService` + REST。完成后即可端到端验证"申请→审批→授权→带表查询→审计"完整纵向用例（docs/10 M2 验收）。
- 或先**查询审计/cancel 真实验证**：跑长查询（如 `SELECT ... FROM information_schema.tables`）验证 cancel + 核验 audit 表写入。

— M2 查询链路（decide→execute→audit）真实可跑、纵深防御生效、恶意语料零写入已端到端验证。完整 M2 验收以 docs/10 §6 全场景 + WarmFlow 审批为准。
