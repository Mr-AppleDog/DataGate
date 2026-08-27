# ADR-008 执行器连接与语句上下文契约缺口

- 状态：已确认（2026-09，执行器切片启动前）
- 背景：ADR-007 冻结的 `QueryExecutor.execute(ExecutionPlan, RowCallback)` 在执行器切片启动时暴露两处缺口：`ExecutionPlan` 只含 `dataSourceId`，**不含连接凭据**（缺口 1）；只含 `statementHash`+`normalizedStatement`，**不含原始可执行 SQL/绑定参数**（缺口 2）。仅凭冻结签名，连接器级执行器既拿不到凭据建连、也拿不到可执行语句。

## 决策

采用 **ConnectionContext 参数方案**（用户决策，2026-09）：

1. `execute` 签名扩展为 `execute(ExecutionPlan plan, ConnectionContext ctx, RowCallback callback)`。
2. 新增 `org.dromara.db.core.domain.ConnectionContext` record：`ConnectionProfile profile` + `SecretValue secret` + `String originalStatement`。
3. 由 **db-executor 编排器**在执行前解析并组装：数据源→ConnectionProfile（经 db-resource）、凭据→SecretValue（经凭据保险箱解密）、原始语句（用户提交、经解析校验）。每次执行新建，用毕销毁 SecretValue。
4. `ExecutionPlan` **保持冻结**——只承载授权信封（decisionId/resourceIds/limits/expiresAt），不混入执行期凭据与语句。

## 理由

- 凭据与原始语句是"执行期上下文"（每次执行解析、短时、销毁），与"授权信封"（不可变、可缓存、可审计）职责不同，分离更干净，且避免把 SecretValue 塞进可缓存的 record。
- 执行器（连接器侧）只接收已组装好的上下文，不自行解析数据源/凭据——连接器保持"只管引擎细节（JDBC/协议/会话保护/流式脱敏）"的定位，不反向依赖 db-resource。
- 纵深防御不受损：执行器仍独立重新解析 `ctx.originalStatement`，非只读/多语句/动作不符一律 REJECTED（编排者被攻陷也无法让 DDL 经执行器落地）。

## 弃用方案

- **resolver 端口方案**（`ExecutionContextResolver` SPI，execute 签名不变、连接器注入端口自解析 planId）：由 `8ad15dbe` agent 在 m2-mysql worktree 内擅自采用（越权提交 `749aad5`/`9b623a7`，已 reset 丢弃）。该方案让连接器反向承担凭据/语句解析、且与"连接器只管引擎细节"的分层冲突，弃用。

## 影响

- `ConnectionContext` 为 ADR-007 修订面，实现者按新签名编码。
- 缺口 2（originalStatement）随 ConnectionContext 一并解决，无需给冻结的 `ExecutionPlan` 加字段。
- 编排器须保证 `originalStatement` 来自经解析校验的输入，禁止用户任意注入未校验语句（执行器再解析为最后防线）。
