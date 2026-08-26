# ADR-007 M2/M3 并行化的契约冻结

- 状态：已确认（2026-09-XX，M2 启动前）
- 背景：M2（授权引擎、审批、MySQL 连接器、查询控制台、查询审计）与 M3（PostgreSQL、Redis/Tair 连接器）拟采用 git worktree 隔离并行实施。为避免并行 agent 在 `ruoyi-db-core` 共享契约上各自漂移、导致合并时大面积冲突，需在并行开始前冻结以下入口契约，作为各 worktree 的公共基线。

## 决策

以下接口在 M2/M3 并行期间视为冻结，变更须经 ADR 修订并由集成者确认：

1. **`QueryExecutor.execute(ExecutionPlan, RowCallback)`** —— 流式执行入口（docs/02 §8.1、docs/06 §4 step 11-12、§11）。执行器接收不可变、服务端构造、已授权的 `ExecutionPlan`，经 `RowCallback` 流式吐出已脱敏行；不持久保存结果正文；返回 `ExecutionResultMeta`（含 executionNo）。`cancel(executionNo)` 取消正在运行的执行，幂等。

2. **`RowCallback`** —— 行回调（`onHeader` / `onRow[→false 终止]` / `onComplete` / `onError`）。列元数据先于行发出；值在服务端流式阶段完成脱敏；二进制只给类型/长度/摘要；单元格硬上限 1 MB。支撑类型 `RowHeader`、`ColumnMeta`、`RowCell`。

3. **`AuthorizationDecisionService.decide(DecisionRequest) → AccessDecision`** —— 鉴权决策入口（docs/03 §7）。单资源判定，显式拒绝优先、默认拒绝、失败关闭；返回 `decisionId` / `allowed` / `reasonCode` / `grantIds` / `limits` / `policyVersion`。orchestrator 逐资源调用，任一拒绝则整体拒绝（docs/06 §4 step 7）。`decisionId` 注入 `ExecutionPlan.decisionId`。支撑类型 `DecisionRequest`、`AccessDecision`、`DecisionLimits`、`MaskingLevel`。

4. **既有冻结面（M0/M1，确认不变）**：`DataSourceConnector` 聚合器、`QueryParser`、`MetadataProvider`、`SlowQueryProvider`、`KekProvider`、`ExecutionPlan`、`ExecutionResultMeta`、`AuditEventInput`、`IAuditService.append/appendIsolated`。

## 影响面

- `ruoyi-db-core`：新增 `execute`/`RowCallback`/`AuthorizationDecisionService` 及支撑 domain/enum；不改既有签名、枚举或错误码。
- 并行切法：
  - 第一轮：**M2-core**（console + executor + auth 实现 `AuthorizationDecisionService` 与 `QueryExecutor.execute`）‖ **M2-mysql**（connector 实现 `QueryParser`/`QueryExecutor`，依赖既有 SPI + 新冻结签名）。
  - 第二轮（M2 合并后）：**M3-pg** ‖ **M3-redis**（同 SPI）。
- M3 连接器不直接调用鉴权服务：执行器接收的 `ExecutionPlan` 已含 `decisionId`，连接器只负责受控执行与流式脱敏。

## 并行纪律（红线）

- 并行期间任何 agent 不得就地修改上述接口、`ruoyi-db-core` 既有枚举/错误码、父 pom 依赖版本。变更须提 ADR 修订并通知集成者，由集成者统一落库后各 worktree rebase。
- 各 worktree 只跑 `mvn package`（reactor 内自解析兄弟模块），禁止并发 `mvn install`（共用同一 `revision` 版本会写坏本地仓库）；合并后由集成者串行 install 一次。
- worktree 建于 ASCII 路径 `C:\dgate-wt\*`，指回主树 `.git`，规避中文路径下 npm/Git Bash 失败（AGENTS.md §5）。
- 集成顺序：M2-core 合并 → M2-mysql rebase 联调 → M3-pg/M3-redis 各自 rebase → 三引擎一致性测试。

## 未收口项（后续切片）

- 多资源查询的 decisionId 聚合策略（逐资源 decide 后如何收敛为单个 plan-level decisionId）由 M2-core orchestrator 决定，本契约只规定单资源 `decide` 原子语义。
- `RowCallback.onRow` 的批量化（JDBC fetchSize 内部缓冲）为实现细节，不影响契约。
