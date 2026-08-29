# DataGate 查询工作台系统测试报告

## 模块结论

- 模块：M2 查询控制台、查询安全失败关闭与取消契约。
- 测试运行：`ST-QRY-001`，覆盖 `ST-QRY-001`～`ST-QRY-004`。
- 综合结论：`FAIL / 不可进入试运行`。无效 SQL 正确失败关闭，但查询取消入口不可用；正常 `SELECT 1` 又被当前环境缺失匹配 KEK 阻塞。
- 最高风险：前端 POST、后端 GET、规范 DELETE 三方错位，用户无法可靠取消长查询。
- 本次未修改业务代码、数据库迁移或持久业务数据。

## 完成的需求与里程碑

- 里程碑：M2（授权与 MySQL 查询）测试切片。
- 覆盖：`RES-006`、`QRY-001`、`QRY-003`、`QRY-005`、`QRY-009`、`QRY-010`。
- 部分覆盖：`AUD-001` 仅检查页面执行元数据和禁止保留结果；没有审计库只读 Oracle，不能判定审计持久化通过。
- 依据：`docs/05-API错误码与状态机规范.md:120,139-145,219-226`、`docs/06-数据源适配与安全执行规范.md:18,22,316,323`、`docs/10-MVP任务拆分与验收标准.md:148-170`。

## 范围与环境

- Git：`main`，提交 `8e94aea694d68438d810359c656c36d1b5354487`；运行环境包含未提交本地变更。
- 后端：Java `17.0.16`、Maven `3.9.6`；前端：Node `22.21.1`、npm `10.9.4`。
- 浏览器：Microsoft Edge + Codex 扩展，复用现有已登录本地测试/管理员会话；未读取或导出 Cookie、Token、Local Storage 或凭据。
- 测试动作仅包括未认证只读 API、页面导航、`SELECT 1`、无效非写入语句和取消按钮；未执行 DML、DDL、Redis 写入、导出、审批或通知。
- 详细配置：[profile.yaml](profile.yaml)，逐项追踪：[matrix.csv](matrix.csv)。

## API 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 4 | 1 | 0 | 1 | 0 | 3 |

- 未认证 `GET /db/datasource/available` 实际 HTTP `200`、业务码 `401`、`data=null`，未返回数据源名称；按规范 HTTP 应为 `401`，判定 `FAIL`，复现已有 [Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1)。
- 其余三个动作由 Edge 持有认证会话；仓库没有非敏感认证 API 测试夹具，因此没有导出浏览器身份到命令行。
- 证据：[unauthenticated-available.json](evidence/api/unauthenticated-available.json)、[reachability.json](evidence/api/reachability.json)。

## Edge 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 4 | 4 | 2 | 1 | 1 | 0 |

- `ST-QRY-001 PASS`：查询控制台可见，选择器列出两个 ACTIVE 数据源；保留证据只记录显示名/引擎类型，未保留端点或凭据字段。
- `ST-QRY-002 BLOCKED`：`SELECT 1` 三次均返回 HTTP `200` / 业务码 `500` 通用错误；脱敏服务端边界是 `IllegalStateException: KEK 版本不可用`。环境缺失匹配 KEK 阻塞正常查询，但错误映射本身形成 `DEF-QRY-001`。
- `ST-QRY-003 PASS`：无效非写入语句显示 `REJECTED`、`QUERY_PARSE_FAILED`、0 行、0 字节，符合失败关闭；没有目标库跟踪与审计 Oracle，不能外推为“确认未到达目标库”。
- `ST-QRY-004 FAIL`：取消操作 4/4 次发送 POST 并被服务端判为方法不支持，页面无稳定反馈，形成 `DEF-QRY-002`。
- 证据目录：[evidence/edge](evidence/edge)。

## 综合结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 4 | 4 | 1 | 2 | 1 | 0 |

综合规则：API 或 Edge 任一 `FAIL` 不能提升为端到端 `PASS`；环境阻塞与产品对阻塞的错误处理分别记录。

## 自动化构建与测试

执行：

```text
mvn -pl ruoyi-modules/ruoyi-db-console,ruoyi-modules/ruoyi-db-executor,ruoyi-modules/ruoyi-db-resource -am -Dtest=<四个定向测试类> -Dsurefire.failIfNoSpecifiedTests=false -DskipTests=false test
```

结果：`PASS`，共 20 项，Failures `0`、Errors `0`、Skipped `0`：

- `DbConsoleServiceImplTest`：4
- `QueryExecutionGatewayImplTest`：9
- `CredentialCryptoServiceTest`：4
- `FileKekProviderTest`：3

限制：这些测试未覆盖取消 HTTP 方法契约，也未覆盖缺失 KEK 到 `CREDENTIAL_KEK_UNAVAILABLE` 的 Web 错误映射。证据：[automated-test-summary.json](evidence/api/automated-test-summary.json)。

## 缺陷与 Issue

- `DEF-EXISTING-001`：未认证 API 使用 HTTP `200` 承载业务码 `401`；`SKIPPED_DUPLICATE`，复用 [Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1)。
- `DEF-QRY-001`（S2）：KEK 不可用被转为 HTTP `200` / 通用业务码 `500`，没有使用已定义的 `CREDENTIAL_KEK_UNAVAILABLE`；`SUBMITTED` 为 [Issue #3](https://github.com/Mr-AppleDog/DataGate/issues/3)。
- `DEF-QRY-002`（S1）：查询取消客户端 POST、后端 GET、规范 DELETE 不一致；`SUBMITTED` 为 [Issue #4](https://github.com/Mr-AppleDog/DataGate/issues/4)。
- 本次已检索 open/closed Issue，未发现 #3/#4 的同根因重复项；草稿归档：[DEF-QRY-001.md](issues/DEF-QRY-001.md)、[DEF-QRY-002.md](issues/DEF-QRY-002.md)。

## 安全、性能与恢复证据

- 安全：无效语法在页面层失败关闭；未认证数据源接口未返回资源名称；所有保留证据已删除端点、私有主机、执行编号、凭据和会话值。
- 安全缺口：没有目标库侧请求跟踪，不能独立确认解析失败请求零到达；没有审计库 Oracle，不能确认拒绝/取消审计落库。
- 性能：`NOT RUN`，本切片未获准进行负载或容量测试。
- 恢复：`NOT RUN`，未执行故障切换、备份或恢复测试。

## 文件、迁移、偏差与 ADR

- 新增/修改文件：`.codex/source-guided-system-test.yaml`、`test-artifacts/system-test/runs/index.csv`，以及 `test-artifacts/system-test/runs/2026-08-30/ST-QRY-001/` 下的测试配置、矩阵、报告、Issue 草稿、JSON 证据与 Edge 截图；业务源码零修改。
- 数据库迁移版本：无。
- 规格偏差：实现路由使用 `/db/console/query` 与 `/db/console/cancel/{executionNo}`，文档使用逻辑资源路径 `/queries:execute` 与 `DELETE /queries/{executionNo}`；本报告按行为契约判定，不要求路径字面完全一致，但 HTTP 方法和幂等语义必须一致。
- ADR：无新增；测试窗口不修改设计决策。

## 未完成项与风险

- 当前 KEK 配置与现有查询凭据不匹配，正常查询、结果限制、脱敏、成功审计和真实 MySQL 执行链路均未验证。
- 取消缺陷使 M2“查询—取消—审计”纵向闭环不成立，长查询资源风险仍存在。
- 未覆盖 PostgreSQL、Redis/Tair、普通用户/不同授权角色、撤权 60 秒、超时、断连、跨节点取消。
- 运行基于脏工作树，修复后必须在可追溯构建上复测。

## 下一最小安全切片

1. 开发窗口对齐取消契约为规范 DELETE，并增加 Controller + 前端契约测试。
2. 将 KEK 缺失稳定映射为 `CREDENTIAL_KEK_UNAVAILABLE`，补充 Web 层回归；测试环境以安全方式恢复匹配 KEK，禁止导出或重新回显凭据。
3. 测试窗口复跑 `SELECT 1`、终态/运行中取消和审计只读 Oracle，再扩展到目标库侧零执行证明。

## 清理

- 创建持久数据：无。
- 清理动作：无；本次所有业务动作均为只读或失败关闭。
