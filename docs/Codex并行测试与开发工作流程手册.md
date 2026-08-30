# Codex 并行测试与开发完整工作流程

## 1. 这份手册解决什么问题

DataGate 的测试、缺陷确认、GitHub Issue、代码修复、Pull Request 回归不是几个互相独立的操作，而是一条有交接条件的完整流水线。

本手册定义：

- 两个真实的 Codex 任务如何协作；
- 三个 skill 在流水线中的职责边界；
- 测试证据如何变成 Issue；
- 开发任务如何在修改代码前先评分并选择模型、思考强度和执行形态；
- Pull Request 如何交还测试任务回归；
- 回归失败后如何回到原开发任务继续修复。

三个 skill 不是三个连续的“按钮”，而是三个角色：

| Skill | 流水线角色 | 是否直接修改业务代码 |
|---|---|---:|
| `source-guided-system-test` | 发现问题、复现、收集证据、提 Issue、PR 回归 | 否 |
| `adaptive-task-router` | 开发前的只读评分和路由门 | 否 |
| `github-issue-implementer` | 消费 Issue 和路由结果，在 Worktree 中实现并创建 PR | 是 |

## 2. 两个 Codex 任务的真实拓扑

完整流程使用两个用户可见的 Codex 任务：

```text
测试任务（Local）
    ├─ 测试原始版本
    ├─ 确认缺陷并提交 Issue
    └─ PR 创建后回归修复版本

开发任务（Worktree）
    ├─ 等待测试任务交付 Issue
    ├─ 先执行 adaptive-task-router 评分
    ├─ 按评分结果选择执行模型和思考强度
    ├─ 调用 github-issue-implementer 实现
    └─ 创建 PR，按回归反馈继续修复
```

两个任务可以提前创建，但存在严格依赖：

- 测试任务没有完成 Issue 交接前，开发任务只能等待，不能自行假设问题成立；
- 开发任务不能在评分门完成前修改代码；
- 测试任务不能在原始 Local 目录顺手修复代码；
- 回归测试必须针对已提交并运行的 PR 分支，不能针对开发 Worktree 中未提交的代码。

“并行”表示两个任务和两个环境可以同时存在，不表示跳过测试证据、Issue 或评分这些前置条件。

## 3. 标准状态机

每一次工作都应能落在以下状态之一：

```text
TEST_PREPARED
  → TEST_EXECUTED
  → DEFECT_CONFIRMED
  → ISSUE_DRAFTED / ISSUE_SUBMITTED / BLOCKED
  → ROUTED
  → IMPLEMENTED
  → PR_OPEN
  → REGRESSION_PASS / REGRESSION_FAIL / REGRESSION_BLOCKED
  → READY_FOR_REVIEW
  → MERGED（仅由有权限的人员执行）
  → ISSUE_CLOSED（确认合并后）
```

状态含义：

- `BLOCKED`：环境、账号、依赖、人工门或权限阻塞，不能当作测试通过；
- `DEFECT_CONFIRMED`：已经观察到与需求或稳定契约矛盾的产品行为；
- `ISSUE_DRAFTED`：已有脱敏草稿，但尚未向 GitHub 发布；
- `ROUTED`：已生成评分、路由模型、思考强度和执行形态；
- `PR_OPEN`：代码已提交、推送并创建 PR；
- `REGRESSION_FAIL`：原问题仍存在或出现新回归，不能合并；
- `READY_FOR_REVIEW`：测试回归通过，但仍需要人工审查和合并。

## 4. 仓库、Worktree 和运行环境

DataGate 主仓库固定为：

```text
E:\github-reposit\DataGate
```

Issue 开发 Worktree 固定放在：

```text
D:\codex-worktree
```

规则：

1. 测试任务使用 Local 环境时，使用主仓库上下文，但禁止修改业务代码。
2. 开发任务使用 Codex 管理的 Worktree，实际目录必须落在 `D:\codex-worktree` 下。
3. 应优先使用 Codex 创建和管理 Worktree；不要在 `E:\github-reposit` 旁边手工创建 Issue Worktree。
4. Codex 已创建的 Worktree 在提交并推送后保留，不要主动 `git worktree remove`，除非用户明确要求清理。
5. 每个任务开始和结束都记录 `git worktree list`、当前分支、HEAD 和工作区状态。
6. 测试环境和 PR 环境同时运行时必须使用不同端口和隔离测试数据。

推荐分支：

```text
codex/issue-<number>-<short-description>
```

### 4.1 GitHub CLI 的沙箱与宿主身份边界

Codex 沙箱和 Windows 管理员宿主会话可能使用不同的 GitHub CLI 身份上下文。管理员终端中的 `gh auth status` 成功，不能证明沙箱能访问同一个 keyring；沙箱返回 `401`、无效 Token 或匿名限流，也不能证明管理员宿主已经退出登录。

标准处理方式：

1. 先在当前 Codex 上下文执行只读 `gh auth status --hostname github.com`，并调用一个实际需要身份的只读端点；
2. 如果沙箱认证失败，但用户或项目确认宿主已登录，则请求批准，仅在宿主环境执行精确的只读 `gh` 命令；
3. 宿主环境必须同时通过认证状态和实际 Issue/PR/CI 端点，之后记录 `github_read_channel: approved_host` 并继续读取；
4. 不得因为沙箱失败就要求用户重新登录，也不得自行执行 `gh auth login/logout/refresh`、显示 Token、读取 keyring 或注入 `GH_TOKEN`；
5. 宿主只读访问获批不等于获准修改 Issue/PR、重跑 CI、推送或创建 PR，外部写操作仍按原授权边界执行。

能够通过获批宿主 `gh` 获取 GitHub 元数据时，不应仅因沙箱认证失败而改用浏览器。浏览器只用于真正需要交互式页面的场景，或宿主 CLI 也不可用时的明确回退。

## 5. 测试任务：从测试到 Issue

测试任务使用 `$source-guided-system-test`，负责以下完整阶段：

### 5.1 准备和授权

在发送请求前记录：仓库、目标环境、UI/API 地址、构建或 commit、角色、测试数据策略、禁止动作和 GitHub Issue 发布模式。

Issue 发布模式必须明确为：

- `disabled`：不产生 Issue 候选；
- `draft_only`：只写本地草稿；
- `submit_after_confirmation`：展示完整标题和正文，得到确认后发布；
- `submit_authorized`：用户已明确授权对指定仓库自动发布。

没有明确发布授权时，默认 `draft_only`，不能把“发现缺陷”自动等同于“有权向外部仓库发 Issue”。

### 5.2 需求和源码

按 DataGate `AGENTS.md` 要求读取 `docs/00` 至 `docs/11`，建立需求、角色、前置条件、API 检查、Edge 操作、可观察 Oracle 和证据路径之间的追踪矩阵。

源码位置只能作为诊断候选。源码推测不能代替运行时证据。

### 5.3 API 检查

先做安全的可达性、认证边界、读取模型、稳定错误码和安全校验请求检查。记录 HTTP 状态、业务码、响应结构、权限结果和脱敏证据。

HTTP 200 不代表业务成功；API `PASS` 也不代表系统测试通过。

### 5.4 Edge 测试

需要浏览器的案例必须使用 Microsoft Edge Codex 扩展并观察用户可见 Oracle。Edge 连接不可用时，按照当前项目规则明确告知用户，再使用允许的应用内浏览器作为回退；不得静默切换到未知浏览器。

每个关键操作都要记录：预期状态、实际可见状态、最终 URL、截图或其他支持证据。不能把“点击成功”当作断言。

不得绕过 CAPTCHA、MFA、权限控制或登录人工门；遇到人工门标记为 `BLOCKED` 并请求用户在 Edge 中完成。

### 5.5 缺陷确认

只有以下条件同时满足时才进入 Issue 阶段：

- 实际行为与需求或稳定契约矛盾；
- 已复现，或一次证据足够强且重复会有风险；
- 是产品缺陷，而不是环境、账号、数据、依赖或权限阻塞；
- 证据已脱敏，可以安全交给仓库维护者。

如果只是环境阻塞，不得创建“产品缺陷 Issue”；但如果产品错误地泄露秘密、返回错误状态或没有遵守失败关闭，也可以针对该产品行为创建 Issue。

### 5.6 Issue 交接包（`test_handoff`）

测试任务创建或准备 Issue 时，必须同时交付一个机器和人都能理解的 handoff package：

```yaml
repository: Mr-AppleDog/DataGate
issue_number: <number-or-null>
issue_url: <url-or-null>
issue_state: DRAFTED | SUBMITTED | SKIPPED_DUPLICATE | BLOCKED
test_run_id: <run-id>
tested_commit: <commit>
environment: <sanitized-environment>
requirements: [QRY-xxx, AUD-xxx]
role: <test-role>
reproduction: <minimal-steps>
expected: <expected-behavior>
actual: <actual-behavior>
reproducibility: <for-example-3/3>
evidence: [<relative-artifact-paths>]
fault_boundary: <observed-boundary>
source_candidates: [<clearly-labeled-candidates>]
unsafe_or_secret_data: false
```

Issue 正文必须包含需求、环境/build、复现步骤、预期/实际、影响、证据、复现率、来源候选和验收条件。不得粘贴密码、Token、Cookie、密钥、SQL 参数、查询结果或未经脱敏的内部日志。

## 6. 开发任务：先评分，再选择模型

开发任务不是“预先指定一个强模型然后开始改代码”。它必须先经过 `$adaptive-task-router` 的评分门。

### 6.1 评分门

评分阶段只读，不能编辑文件、提交、推送、创建 PR 或委派第二个实现任务。评分器必须返回结构化结果，至少包含：

```yaml
score: 0-100
dimensions:
  complexity: 0-5
  ambiguity: 0-5
  risk: 0-5
  context_size: 0-5
  verification_cost: 0-5
  coordination: 0-5
route:
  model: <selected-model>
  thinking: <selected-reasoning-effort>
  service_tier: <selected-or-null>
  execution: single | parallel | ultra
  review: none | main | read-only-reviewer
risk_flags: []
workstreams: []
rationale: []
```

评分权重固定为：复杂度 20%、歧义 15%、风险 25%、上下文 10%、验证成本 15%、协作 15%。

模型型号、`thinking/reasoning effort`、服务等级和执行形态是四个不同字段，不能只说“使用高强度模型”而不记录具体值。

### 6.2 路由规则

评分只是基线，安全硬覆盖优先：

| 总分 | 基线执行模型 | 思考强度 |
|---:|---|---|
| 0–20 | GPT-5.6 Luna | low |
| 21–40 | GPT-5.6 Luna | medium |
| 41–60 | GPT-5.6 Terra | medium |
| 61–75 | GPT-5.6 Terra | high |
| 76–90 | GPT-5.6 Sol | xhigh |
| 91–100 | GPT-5.6 Sol | max |

涉及认证、授权、凭据、加密、审计、数据库迁移、生产事故或其他安全边界时，最低使用 GPT-5.6 Sol `high`；涉及不可逆变更、潜在数据损失或复杂安全诊断时，使用 GPT-5.6 Sol `xhigh` 或 `max`。

评分结果格式错误时只允许请求一次修正；仍然错误时使用保守路由并在日志中说明。高风险模型不可用时必须暂停，不得静默降级。

### 6.3 路由之后

只有 `ROUTED` 结果存在后，开发任务才可以调用 `$github-issue-implementer`。实现任务必须收到：

- Issue URL/编号和当前状态；
- 测试 handoff package；
- 选定模型、thinking、service tier、执行形态和审查安排；
- 允许修改的目录和禁止修改的范围；
- 验收标准、测试命令和不应执行的外部动作。

用户要求“两个不同模型”时，测试任务模型和开发执行模型应记录并比较；如果评分结果与“必须不同模型”冲突，应报告冲突，不得为了形式强行使用不合适的模型。

## 7. Issue 实现、提交和 Pull Request

开发任务必须在独立 Worktree 中：

1. 核对 Issue 仍属于当前仓库、仍可执行、没有重复 PR 或已合并修复；
2. 从当前默认分支创建新的 `codex/` 分支；
3. 先用红测或最小复现确认问题，再实现最小完整修复；
4. 增加能够证明“修复前失败、修复后通过”的聚焦回归测试；
5. 执行构建、测试和安全检查，不能把编译成功写成测试通过；
6. 检查 diff、生成文件、秘密、日志、迁移和无关格式化；
7. 按仓库 `AGENTS.md` 的提交规则提交；
8. 推送分支并创建 PR，禁止自动合并。

提交语言遵循以下优先级：仓库 `AGENTS.md` > 项目贡献规范 > 本手册默认值。手册不再硬编码英文或中文。DataGate 当前项目规则要求中文提交信息，因此开发任务在 DataGate 使用中文提交信息。

PR 必须包含：Issue、需求 ID、测试 run ID、根因、修改文件、迁移、测试命令和结果、安全影响、风险、回滚方式及回归入口。只有在 PR 完整解决 Issue 且目标分支正确时才使用 `Closes #<number>`。

## 8. PR 回归和失败闭环

PR 创建后，测试任务重新调用 `$source-guided-system-test`，但运行目标必须是 PR 分支已经启动的服务。开发任务向测试任务交付 `pr_handoff`，其中包含 PR、分支、commit、服务地址、启动证据、原始 `test_run_id` 和已验证范围。

如果 PR 检查失败，先只读确认 PR head 分支和 SHA，再按“该 SHA 的 runs → 失败 job/step → annotations → 失败日志 → PR diff”定位根因。annotations 中的弃用警告或通用退出码不能直接当作根因；必须根据失败日志区分基础设施、依赖、编译、测试编译、测试、打包或产品回归。根因属于原 Issue 路由范围时，在同一个 Worktree、分支和 PR 中修复并推送新提交；范围实质扩大时重新评分或请求用户决定。

回归至少验证：

- Issue 原始复现步骤；
- 预期结果、HTTP/业务错误码和用户可见状态；
- 权限、失败关闭、审计、脱敏和未到达目标库等安全断言；
- 相关相邻场景；
- 构建、API、Edge 和日志证据。

回归失败时：

1. 测试任务在 PR 中记录失败步骤和证据；
2. 不合并 PR，不创建重复 Issue/PR；
3. 将失败反馈交还原开发任务；
4. 开发任务在原 Worktree、原分支继续修复；
5. 推送新提交；
6. 测试任务只重新执行受影响场景和必要相邻回归。

回归通过后，测试任务在 PR 中记录通过证据，状态进入 `READY_FOR_REVIEW`。合并和关闭 Issue 由有权限的人员执行，不由 skill 自动完成。

## 9. 测试数据、端口和安全边界

- 原始版本和 PR 版本使用不同端口；
- 使用独立测试库、Schema、租户或数据前缀；
- 写测试数据必须可识别、可清理且不影响既有数据；
- 不得对生产数据库执行写入、破坏性 SQL 或不可逆操作；
- 查询账号、变更账号、采集账号必须隔离；
- 日志和证据只能记录 ID、fingerprint、耗时、错误码等安全元数据；
- 审计写入失败时，高风险动作必须失败关闭；
- 不得使用 Anaconda；
- 不得把用户 SQL 交给 dynamic-datasource 承载；
- 不得提交密码、Token、私钥、KEK、DEK、Cookie 或连接字符串。

## 10. 证据和交付报告

系统测试证据必须保存在目标项目内：

```text
<project-root>/test-artifacts/system-test/runs/<date>/<run-id>/
  profile.yaml
  matrix.csv
  report.md
  evidence/api/
  evidence/edge/
  issues/
```

开发任务的路由和实现日志也必须位于目标项目或其 Worktree 内，不能写入 skill 安装目录或其他仓库。

每次交付报告至少包含：

1. 需求 ID 和里程碑；
2. 测试任务、开发任务、模型和 Worktree；
3. Issue/PR 状态和 URL；
4. 修改文件和数据库迁移；
5. 精确命令及 PASS/FAIL/BLOCKED 结果；
6. API 与 Edge 分开统计；
7. 安全、性能、恢复证据；
8. 偏差、阻塞和剩余风险；
9. 下一步最小安全切片。

`BLOCKED` 和 `NOT RUN` 永远不能计入通过；在 DataGate 的 13 个最终验收场景全部通过前，只能报告已完成对应里程碑或可进入试运行。

## 11. 最小操作清单

### 测试任务

```text
Local
→ 读取 AGENTS.md、docs/00–11 和测试设计
→ 安全 API 检查
→ Microsoft Edge 用户路径
→ 记录 API/Edge/源码证据
→ 判断 PASS、FAIL 或 BLOCKED
→ 去重并生成/提交 Issue
→ PR 创建后回归并反馈 PR
```

### 开发任务

```text
Worktree（D:\codex-worktree）
→ 接收 Issue handoff package
→ 调用 adaptive-task-router 只读评分
→ 记录 model / thinking / service_tier / execution
→ 调用 github-issue-implementer
→ 红测 → 最小修复 → 聚焦测试
→ 按仓库规则 Commit
→ Push → 创建 PR
→ 接收回归反馈并在原分支修复
```

### 需要暂停询问用户的情况

需要真实生产系统、真实凭据、公司网络、改变支持引擎/审批链/审计保留期、弱化安全约束、购买有许可证风险的组件、处理破坏性数据变更、或产品需求存在未决冲突时，必须暂停并询问用户。
