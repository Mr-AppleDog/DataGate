# DataGate 凭据元信息模块测试报告

## 模块结论

- 模块：凭据列表的认证边界和秘密不回显。
- 测试用例：`ST-CRED-001`。
- 综合结论：`FAIL`。Edge 管理员面板仅显示元信息，但未认证 API 仍以 HTTP `200` 承载业务码 `401`。
- 本次只读执行未新增、修改、测试或禁用凭据。

## 范围与依据

- 需求与规范：`docs/01-产品需求规格说明书.md:174`、`docs/05-API错误码与状态机规范.md:28`、`docs/08-安全审计与威胁模型.md`。
- 源码链路：`plus-ui/src/api/db/datasource.ts` 的 `listCredentials`；`plus-ui/src/views/db/datasource/index.vue` 的凭据管理抽屉。
- UI 入口：数据源管理页中 `smoke-mysql` 的凭据管理按钮。
- Edge 身份：现有已登录管理员会话；未导出 Cookie、Token 或会话存储。
- API fixture：已认证数据源列表响应中的非秘密数据源 ID，仅用于拼接只读路径。
- 排除范围：添加凭据、输入密码、测试连接、禁用凭据、轮换凭据及任何持久化变更。

## API 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

请求：未认证访问 `/dev-api/db/credential/list/<data-source-id>`。

- 实际：HTTP `200`、响应业务码 `401`、`data=null`。
- 预期：HTTP `401`，稳定认证失败响应，不包含用户名、密码、密文或 Nonce。
- 证据：[unauthenticated-credential-list.json](evidence/api/unauthenticated-credential-list.json)。
- API 状态：`FAIL`。该失败与 [Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1) 的认证异常 HTTP 映射症状相同。

## Edge 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 0 | 0 | 0 |

执行：打开 `smoke-mysql` 的凭据管理面板并读取可见字段。

- 观察到的表头：用途、用户名、状态、创建时间、操作。
- 观察到的记录：1 条查询账号元信息。
- 未观察到：密码输入框、密码值、密文、Nonce。
- 证据：[credential-metadata.json](evidence/edge/credential-metadata.json) 和 [credential-panel.png](evidence/edge/credential-panel.png)。
- Edge 状态：`PASS`。

## 综合计数

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

综合规则：API `FAIL` + Edge `PASS` 不能提升为端到端 `PASS`；认证失败的 HTTP 状态契约仍未满足。

## 缺陷与 Issue

- 关联缺陷：`DEF-001`，未认证 DataGate API 使用 HTTP `200` 返回业务码 `401`。
- Issue 状态：`SKIPPED_DUPLICATE`，复用已提交的 [Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1)，未创建重复议题。
- 秘密保护观察：当前 Edge 凭据列表未显示密码、密文或 Nonce，符合“密码只写、列表仅元信息”的用户可见要求。
- 源码候选仍是待验证假设：认证异常处理器可能未设置 HTTP 响应状态；本次没有实施修复。

## 覆盖缺口与风险

- 未测试添加凭据时密码是否只写一次、提交后表单是否清空。
- 未测试凭据禁用、轮换和连接测试流程。
- 未测试其他角色对凭据元信息的对象级权限。
- API 认证状态映射问题可能影响网关、监控和标准客户端判断。

## 清理

- 创建数据：无。
- 清理动作：无；全程只读。
