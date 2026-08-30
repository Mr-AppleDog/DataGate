# DataGate PR #2 回归报告

## 结论

- PR 回归结论：`BLOCKED`
- 代码门禁：`PASS`
- 原缺陷用例：未能在真实 PR 服务上执行，不能标记为通过或失败。
- 运行时发布建议：在可正常创建 Windows NIO selector 的环境中重跑未认证 API 与 Edge 用例后，再进入人工评审和合并。

> 事后状态（2026-08-30）：PR #2 随后已由外部操作合并，Issue #1 已关闭。该仓库状态变化不追溯修改本次系统回归的 `BLOCKED` 结论。

## 执行对象

| 字段 | 值 |
|---|---|
| Issue | [#1](https://github.com/Mr-AppleDog/DataGate/issues/1) |
| Pull Request | [#2](https://github.com/Mr-AppleDog/DataGate/pull/2) |
| 分支 | `codex/issue-1-http-status` |
| PR 提交 | `437dea36f4f74400e998401a8bfaa1b2d98d293d` |
| Worktree | `D:/codex-worktree/pr2-regression/DataGate` |
| 需求 | `RES-006`、HTTP `401` 认证失败契约 |
| 测试模式 | `pr_regression` |

## 构建与测试

| 检查 | 结果 | 证据 |
|---|---|---|
| Sa-Token + executor 聚焦测试 | PASS；16/16 模块成功，Sa-Token 1/1，executor 37/37 | [focused-regression.md](evidence/tests/focused-regression.md) |
| 后端打包 | PASS；44/44 模块成功 | [startup-blocker.md](evidence/runtime/startup-blocker.md) |
| GitHub `build` | PASS；6m16s | [run-33293392791.md](evidence/ci/run-33293392791.md) |
| GitHub `security-report` | PASS；9m16s | [run-33293392791.md](evidence/ci/run-33293392791.md) |

本地执行 `mvn clean install -B -DskipTests=false` 时，Issue #1 契约测试与原 CI 根因模块均通过；后续 `WebhookNotificationChannelTest` 因同一主机 loopback selector 异常在构造阶段报错。GitHub Actions 干净环境中的完整 `verify` 已通过，因此该本地异常按环境偏差记录。

## API 结果

| 计划 | 执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0 | 0 | 1 | 0 |

未认证数据源列表请求在发送前被环境阻断。详情见 [API 证据](evidence/api/unauthenticated-datasource-list.md)。

## Edge 结果

| 计划 | 执行 | PASS | FAIL | BLOCKED | NOT RUN |
|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0 | 0 | 1 | 0 |

由于 PR 后端未监听，不能把之前主分支上的 Edge 筛选结果复用为 PR 通过。详情见 [Edge 证据](evidence/edge/datasource-filter.md)。

## 综合结果

| 用例 | API | Edge | 综合状态 |
|---|---|---|---|
| `ST-RES-006-PR2` | BLOCKED | BLOCKED | BLOCKED |

阻断边界已定位到当前 Windows Codex 宿主的 JDK selector/XNIO provider；没有观察到产品行为矛盾，也没有发送应用写请求。

## 安全与数据

- 未认证契约单测同时断言传输状态和业务码为 `401`。
- 响应、日志和证据中未记录 token、Cookie、凭据、SQL 参数或查询结果。
- 本轮未执行数据库业务写入；Flyway 检查显示 schema 已是版本 21，无迁移执行。
- 未启动 Edge 操作，未导出会话。

## 偏差、风险与下一步

- 无数据库迁移、无 ADR 偏差、无生产逻辑扩展。
- PR 历史中已有英文提交信息，与仓库当前“提交信息只准使用中文”规则不一致；本轮未强推改写历史。
- 系统级回归尚未通过，所以不能报告 `READY_FOR_REVIEW`，也不能合并或关闭 Issue。
- 下一步最小安全切片：在可启动 PR `437dea3` 服务的环境中，重跑原未认证 API；确认 HTTP `401` 且无资源名称后，再用 Microsoft Edge 重跑数据源名称筛选。

## PR feedback

`BLOCKED`：代码、聚焦测试、GitHub build 和安全报告均通过；真实 PR 服务受当前宿主 selector/XNIO 环境阻断，原 API 与 Edge 用例仍待重跑。未向 PR 写评论，因为原测试授权明确禁止 GitHub 评论。
