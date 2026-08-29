# DataGate 单用例系统测试报告

## 发布结论

- 总体结论：`结论不足`
- 单用例结论：`FAIL`
- 最高风险问题：未认证数据源列表请求使用 HTTP `200` 承载业务码 `401`，与 HTTP 状态规范不一致。
- 发布建议：资源名称防泄露和 Edge 筛选行为通过，但应修复认证失败的 HTTP 状态契约后重测；本次仅执行 1 条用例，不能据此判断版本可发布。

## 执行环境

| 字段 | 值 |
|---|---|
| 平台 / 仓库 | DataGate / `E:/github-reposit/DataGate` |
| 环境 / UI 基础地址 | local / `http://localhost` |
| API 基础地址 | `http://127.0.0.1:8080` |
| 构建版本 / Git 提交 | `8e94aea` |
| 测试模式 | Smoke，单用例 |
| API 客户端 / 认证方式 | PowerShell HTTP 客户端 / 未认证负向请求 |
| Edge 扩展连接 | Microsoft Edge 扩展，复用现有已登录会话 |
| 测试角色 | 未认证 API 调用方；Edge 现有管理员会话 |
| 执行日期 / 时区 | 2026-08-30 / Asia/Shanghai |

## 测试范围与授权边界

- 包含需求：`RES-006` 无权限用户不能发现资源名称；管理员可检索数据源非秘密信息。
- 包含源码链路：`GET /db/datasource/list`、前端 `/data-assets/datasources` 名称筛选。
- 排除范围：数据源新增、编辑、凭据、连接测试、启停、同步和查询执行。
- 测试数据：现有 `smoke-mysql` 记录，仅用于名称匹配。
- 禁止操作：任何写入、凭据或会话导出、GitHub 发布。
- GitHub Issue 模式：`submit_authorized`，目标仓库 `Mr-AppleDog/DataGate`

## API 测试结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

| 用例编号 | 方法 / 端点 | 预期契约 | 实际结果 | 状态 |
|---|---|---|---|---|
| ST-RES-006 | `GET /db/datasource/list`，无认证，带名称过滤 | HTTP `401`，稳定认证失败响应，不包含受保护名称 | HTTP `200`，业务码 `401`，正文不包含受保护名称 | `FAIL` |

API 证据：[unauthorized-datasource-list.json](evidence/api/unauthorized-datasource-list.json)

## Edge 浏览器测试结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 0 | 0 | 0 |

| 用例编号 | 用户操作 | 可见断言 | 实际结果 | 状态 |
|---|---|---|---|---|
| ST-RES-006 | 打开数据源管理；重置；输入 `smoke-mysql`；点击“搜索” | 重置后 3 条；筛选后仅匹配行，总数 1 | 重置后 3 条；筛选后 `smoke-mysql` 1 行，其他 2 行不可见，“共 1 条” | `PASS` |

Edge 证据：[datasource-filter-pass.png](evidence/edge/datasource-filter-pass.png)

## 综合结果

| 计划用例 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

| 用例编号 | API 状态 | Edge 状态 | 综合状态 | 说明 |
|---|---|---|---|---|
| ST-RES-006 | `FAIL` | `PASS` | `FAIL` | 用户可见筛选和名称防泄露正确，但认证失败 HTTP 状态违反接口规范。 |

## 缺陷清单

### DEF-001 — 未认证数据源列表返回 HTTP 200 而非 HTTP 401

- 严重程度 / 影响：`S2 Medium`。资源数据未泄露，但标准 HTTP 客户端、网关、监控和安全策略可能把认证失败误判为成功请求。
- 需求来源：`docs/05-API错误码与状态机规范.md` 的 HTTP `401` 认证失败语义；`RES-006`。
- 最小复现步骤：不携带认证信息请求 `GET /db/datasource/list?name=<existing-name>&pageSize=10&pageNum=1`。
- 预期结果：HTTP `401`，响应不包含资源名称。
- 实际结果：HTTP `200`，响应体业务码 `401`，未包含资源名称。
- 复现概率：`1/1`。
- 证据支持的故障边界：认证异常到 HTTP 响应映射层。
- 源码候选：`SaTokenExceptionHandler.handleNotLoginException` 返回 `R.fail(401, ...)`，但未设置 HTTP 响应状态。该位置是证据支持的候选，尚未实施修复验证。
- 关联用例：`ST-RES-006`。

## GitHub Issue 处理

| 缺陷编号 | 仓库 | Issue 状态 | Issue URL | 备注 |
|---|---|---|---|---|
| DEF-001 | `Mr-AppleDog/DataGate` | `SUBMITTED` | https://github.com/Mr-AppleDog/DataGate/issues/1 | 已去重并脱敏；通过项目级 `submit_authorized` 策略自动提交。 |

## 覆盖缺口与剩余风险

- 未测试其他角色、资源类型、环境/类型组合和分页边界。
- 未测试数据源写操作、凭据、查询执行及其他引擎。
- 未获取构建提交标识，因此证据只绑定本地运行时间。
- 单条用例不能代表系统发布门禁。

## 测试数据与清理

- 创建的测试记录：无。
- 已完成的清理：无需清理；测试只改变前端筛选条件。
- 未清理项目：无。
