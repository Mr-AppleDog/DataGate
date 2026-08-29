# DataGate 列脱敏标签模块测试报告

## 模块结论

- 模块：列脱敏标签查询与空数据状态。
- 测试用例：`ST-MASK-001`。
- 综合结论：`FAIL`。Edge 查询显示稳定空状态且未泄露列信息，但未认证 API 仍以 HTTP `200` 承载业务码 `401`。
- 本次只读执行未修改脱敏标签或其他业务数据。

## 范围与依据

- 需求与设计：`docs/04-数据模型与数据库表设计.md`、`docs/05-API错误码与状态机规范.md:28`、`docs/08-安全审计与威胁模型.md`。
- 源码链路：`plus-ui/src/api/db/columnProfile.ts` 的列配置读取接口；Edge 路由 `/data-assets/column-profile`。
- 测试数据：页面默认表资源 ID `1`，现有数据为空；未创建测试数据。
- Edge 身份：现有已登录管理员会话；未导出 Cookie、Token 或会话存储。
- 排除范围：脱敏标签新增、编辑、分类变更、查询结果导出。

## API 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

请求：未认证访问 `/dev-api/db/column-profile/1`。

- 实际：HTTP `200`、响应业务码 `401`、`data=null`。
- 预期：HTTP `401`，稳定认证失败响应，不包含列资源数据。
- 证据：[unauthenticated-column-profile.json](evidence/api/unauthenticated-column-profile.json)。
- API 状态：`FAIL`。该失败与 [Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1) 的认证异常 HTTP 映射症状相同。

## Edge 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 0 | 0 | 0 |

执行：从“数据资产”菜单进入“列脱敏标签”，保留默认表资源 ID `1`，点击“查询列”。

- 页面终态：显示“暂无数据”。
- 可见列资源行数：`0`。
- 未观察到未授权列名、路径、敏感等级或脱敏规则。
- 证据：[column-profile.json](evidence/edge/column-profile.json) 和 [column-profile.png](evidence/edge/column-profile.png)。
- Edge 状态：`PASS`。

## 综合计数

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

综合规则：API `FAIL` + Edge `PASS` 不能提升为端到端 `PASS`。

## 缺陷与 Issue

- 关联缺陷：`DEF-001`，DataGate 未认证 API 使用 HTTP `200` 返回业务码 `401`。
- Issue 状态：`SKIPPED_DUPLICATE`，复用已提交的 [Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1)，未创建重复议题。
- Edge 空状态及未泄露列信息符合当前可观察要求；没有发现新的独立缺陷。

## 覆盖缺口与下一步

- 未测试有列配置数据时的敏感等级、脱敏类型和权限过滤。
- 未测试列脱敏标签编辑、保存、刷新持久化和服务端权限拒绝。
- 下一最小切片：继续测试工作台的已启用数据源列表和查询失败关闭路径，严格禁止执行写入 SQL。

## 清理

- 创建数据：无。
- 清理动作：无；全程只读。
