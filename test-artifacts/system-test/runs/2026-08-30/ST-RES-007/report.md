# DataGate 资源数据源列表模块测试报告

## 模块结论

- 模块：资源数据源列表与名称筛选。
- 测试用例：`ST-RES-007`。
- 综合结论：`FAIL`。Edge 筛选在服务恢复后通过，但未认证 API 仍以 HTTP `200` 承载业务码 `401`，不符合 HTTP 认证失败契约。
- 本次只读执行未创建或修改业务数据。

## 范围与依据

- 需求与规范：`docs/01-产品需求规格说明书.md:174`、`docs/05-API错误码与状态机规范.md:28`、`docs/05-API错误码与状态机规范.md:221`。
- 源码链路：`plus-ui/src/api/db/datasource.ts` 的 `GET /db/datasource/list`；`plus-ui/src/views/db/datasource/index.vue` 的 `queryParams.name`、搜索按钮和列表渲染。
- UI 路由：`/data-assets/datasources`。
- API 实际入口：开发环境 `/dev-api`，由 Vite 代理到 `127.0.0.1:8080`。
- Edge 身份：复用现有已登录管理员会话；未导出 Cookie、Token 或会话存储。
- 排除范围：数据源新增、编辑、凭据、连接测试、启停、元数据同步和查询执行。

## API 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

请求：未认证访问 `/dev-api/db/datasource/list?pageSize=10&pageNum=1`。

- 第一次观测：HTTP `500`，属于短暂服务异常，证据见 [unauthenticated-list.json](evidence/api/unauthenticated-list.json)。
- 第二次观测：HTTP `200`、响应业务码 `401`、无资源数据，证据见 [unauthenticated-list-attempt-2.json](evidence/api/unauthenticated-list-attempt-2.json)。
- 预期：HTTP `401`，稳定认证失败响应，不包含资源名称。
- API 状态：`FAIL`。失败边界与已提交的 Issue #1 相同，不重复创建议题。

## Edge 结果

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 0 | 0 | 0 |

执行路径：打开数据源管理页，点击“重置”，输入 `smoke-mysql`，点击“搜索”。

- 首次服务异常时：页面显示“系统接口500异常”，仍保留旧的 3 行数据；证据见 [datasource-filter-failure.json](evidence/edge/datasource-filter-failure.json)、[browser-errors.json](evidence/edge/browser-errors.json) 和 [datasource-filter-failure.png](evidence/edge/datasource-filter-failure.png)。这是风险观察，未单独判定为新的产品缺陷。
- 服务恢复后：Edge 请求带有名称筛选参数，响应 HTTP `200`、总数 `1`、行数 `1`；证据见 [datasource-filter-network.json](evidence/edge/datasource-filter-network.json)。
- 最终可见状态：仅显示 `smoke-mysql`，页面显示“共 1 条”；证据见 [datasource-filter-pass.json](evidence/edge/datasource-filter-pass.json) 和 [datasource-filter-pass.png](evidence/edge/datasource-filter-pass.png)。
- Edge 状态：`PASS`。

## 综合计数

| 计划 | 已执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 0 | 1 | 0 | 0 |

综合规则：API `FAIL` + Edge `PASS` 不能提升为端到端 `PASS`；认证 HTTP 状态契约仍失败。

## 缺陷与 Issue

- `DEF-001`：未认证数据源列表返回 HTTP `200` 而非 HTTP `401`。
- 已有 Issue：[Issue #1](https://github.com/Mr-AppleDog/DataGate/issues/1)，本次确认仍是同一认证异常映射症状，状态 `SUBMITTED`。
- 本次 Issue 状态：`SKIPPED_DUPLICATE`，未创建重复 Issue。
- 源码候选仍仅作为假设：认证异常处理器可能只构造了业务错误体而未设置 HTTP 响应状态；本次没有实施代码修复。

## 风险与下一步

- 风险：API 客户端、网关和监控可能把认证失败误判为成功请求。
- 风险观察：服务短暂 500 时列表保留旧数据，可能造成用户误判筛选结果；需要后续在稳定服务状态下单独确认是否符合 UI 错误态设计。
- 覆盖缺口：未测试环境/类型组合、分页边界、详情权限、凭据元信息和写操作。
- 下一最小切片：在服务稳定后继续资源模块的详情权限与凭据“只写不可读”检查，仍保持 API 与 Edge 分离执行。

## 清理

- 创建数据：无。
- 清理动作：无；全程只读。
