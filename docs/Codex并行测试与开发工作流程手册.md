# Codex 并行测试与开发工作流程手册

## 1. 文档目的

本手册用于指导 DataGate 项目通过两个 Codex 任务并行完成系统测试、问题确认、代码修改、回归验证和 Pull Request 交付。

推荐模式：

- 测试任务使用 Local 环境，只负责测试、复现和收集证据。
- 开发任务使用独立 Git Worktree，只负责修改代码和提交 Pull Request。
- 两个任务不得同时修改同一个工作目录。
- 测试任务确认问题后创建 GitHub Issue。
- 开发任务根据 Issue 创建分支、实现修复并创建 Pull Request。
- 修复完成后，由测试任务针对 Pull Request 分支运行的服务执行回归测试。

## 2. 总体工作流

```text
测试任务发现问题
    -> 确认问题属实
    -> 创建 GitHub Issue
    -> 开发任务创建独立 Worktree
    -> 创建 codex/ 前缀分支
    -> 修改代码并执行开发测试
    -> Git Commit
    -> Git Push
    -> 创建 Pull Request
    -> 测试任务验证 Pull Request 分支
    -> 记录回归测试结果
    -> 代码审查
    -> 合并 Pull Request
    -> 关闭 GitHub Issue
```

## 3. 为什么必须使用 Worktree

Git Worktree 可以为同一个 Git 仓库创建多个相互隔离的工作目录。每个 Worktree 拥有独立的文件副本，可以检出不同分支，但共享同一个仓库的 Git 提交数据。

使用 Worktree 的主要原因：

- 防止测试任务与开发任务覆盖彼此的文件。
- 防止一个任务读取到另一个任务尚未完成的代码。
- 允许测试环境和修复环境同时运行。
- 允许开发任务独立提交、推送和创建 Pull Request。
- 避免两个 Codex 任务同时写入 DataGate 的 Local 工作目录。

官方参考：[Codex Git Worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees)

## 4. 两个任务的职责划分

### 4.1 测试任务

推荐环境：`Local`

推荐 Skill：`01 源码引导系统测试`

内部调用名称：`$source-guided-system-test`

测试任务负责：

- 按项目文档了解需求和验收标准。
- 检查当前项目是否可以启动和访问。
- 先执行安全的 API 检查。
- 使用 Microsoft Edge 浏览器执行页面测试。
- 复现用户报告的问题。
- 记录请求、响应、错误信息、日志和截图。
- 判断问题是否真实存在。
- 确认缺陷后创建 GitHub Issue。
- 修复完成后执行回归测试。
- 将回归结果反馈到 Pull Request。

测试任务禁止：

- 修改业务代码。
- 未经授权修改数据库结构或生产数据。
- 为了让测试通过而临时改变需求或安全规则。
- 在没有证据的情况下直接创建缺陷 Issue。
- 自动合并 Pull Request。

### 4.2 开发任务

推荐环境：`Worktree`

推荐 Skill：`03 自适应任务路由`和`02 GitHub Issue 实现`

内部调用名称：

- `$adaptive-task-router`
- `$github-issue-implementer`

开发任务负责：

- 获取并阅读指定的 GitHub Issue。
- 复核 Issue 是否可以在当前代码中得到验证。
- 使用自适应任务路由评估任务难度。
- 在独立 Worktree 中创建 `codex/` 前缀分支。
- 按 DataGate 需求文档和安全规则修改代码。
- 执行与修改范围相关的测试。
- 使用英文 Git 提交信息提交代码。
- 推送开发分支。
- 创建 Pull Request。
- 在 Pull Request 中关联 GitHub Issue。
- 根据回归测试结果继续修复。

开发任务禁止：

- 直接修改测试任务使用的 Local 工作目录。
- 使用中文 Git 提交信息。
- 绕过 DataGate 文档中规定的安全要求。
- 未经用户明确授权自动合并 Pull Request。
- 未确认 Issue 属实就进行大范围代码修改。

## 5. 第一步：启动测试任务

在 DataGate 项目的 Local 环境中创建测试任务，使用以下提示词：

```text
使用 $source-guided-system-test 测试当前 DataGate 项目。

只负责测试、复现问题和收集证据，不修改业务代码。
按照项目文档确认需求，先执行安全的 API 检查，再使用 Microsoft Edge 浏览器完成页面测试。
如果确认存在缺陷，创建 GitHub Issue，并写清楚复现步骤、预期结果、实际结果、影响范围和测试证据。
```

测试任务应输出：

- 测试目标。
- 测试环境。
- 前置条件。
- 操作步骤。
- 预期结果。
- 实际结果。
- 请求和响应证据。
- 浏览器截图。
- 日志或错误码。
- 是否确认属于缺陷。
- GitHub Issue 地址或 Issue 草稿。

## 6. 第二步：创建 GitHub Issue

只有问题能够稳定复现并且与需求不一致时，才创建 GitHub Issue。

Issue 建议包含：

```markdown
## Problem

描述实际发生的问题。

## Requirements

列出相关需求 ID 和需求文档。

## Steps to reproduce

1. 准备测试环境。
2. 执行具体操作。
3. 观察页面、接口或日志结果。

## Expected behavior

描述按照需求应该出现的结果。

## Actual behavior

描述实际出现的结果。

## Evidence

提供截图、错误码、接口响应和必要日志。

## Impact

描述影响范围、安全风险和用户影响。

## Acceptance criteria

- 原始问题无法再次复现。
- 相邻功能没有出现行为回归。
- 相关自动化测试通过。
- 安全规则和审计要求保持有效。
```

Issue 标题和代码标识可以使用英文。Issue 正文可以根据团队协作要求使用中文。

## 7. 第三步：启动开发任务

在 Codex 中新建任务时执行以下操作：

1. 选择 DataGate 项目。
2. 在环境选项中选择 `Worktree`。
3. 选择正确的起始分支，例如 `main` 或当前里程碑分支。
4. 不要让开发任务直接运行在测试任务使用的 Local 目录中。
5. 将已经确认的 GitHub Issue 编号交给开发任务。

开发任务提示词：

```text
先使用 $adaptive-task-router 评估并委派这个任务，
然后使用 $github-issue-implementer 实现 GitHub Issue #123。

要求：
- 在当前独立 Worktree 中工作
- 创建 codex/ 前缀的开发分支
- 严格遵守 DataGate 的 AGENTS.md 和 docs 需求基线
- 修改代码并执行必要测试
- Git 提交信息必须使用英文
- 推送分支并创建 Pull Request
- Pull Request 关联并关闭 Issue #123
- 不自动合并 Pull Request
```

将示例中的 `#123` 替换为实际 Issue 编号。

## 8. 第四步：开发分支和提交规范

推荐分支名称：

```text
codex/issue-123-short-description
```

推荐提交信息：

```text
fix: enforce datasource permission checks
```

```text
fix: prevent unauthorized workflow reassignment
```

```text
test: add regression coverage for issue 123
```

提交要求：

- Git 提交信息必须使用英文。
- 一个提交应围绕一个清晰目标。
- 不得提交密码、Token、私钥和生产凭据。
- 不得提交与 Issue 无关的大范围格式化修改。
- 不得覆盖用户已有但与本 Issue 无关的改动。

## 9. 第五步：创建 Pull Request

Pull Request 应包含：

- 问题背景。
- 关联 Issue。
- 需求 ID。
- 根因分析。
- 修改方案。
- 修改文件。
- 数据库迁移版本。
- 执行的测试和结果。
- 安全影响。
- 兼容性影响。
- 未完成项和风险。
- 回滚方式。

Pull Request 正文示例：

```markdown
## Summary

修复 Issue #123 中描述的问题。

## Root cause

说明问题根因。

## Changes

- 修改相关领域服务。
- 增加服务端权限检查。
- 增加回归测试。

## Requirements

- AUTH-xxx
- AUD-xxx

## Validation

- 执行的构建命令和结果。
- 执行的测试命令和结果。
- API 或浏览器验证结果。

## Security impact

说明是否涉及权限、凭据、审计、脱敏或受控执行。

## Risks

列出剩余风险和未覆盖场景。

Closes #123
```

## 10. 第六步：并行启动测试环境

如果原始版本和修复版本需要同时运行，必须使用不同端口。

| 环境 | 后端端口示例 | 前端端口示例 |
|---|---:|---:|
| 原始测试环境 | `8080` | `5173` |
| Pull Request 修复环境 | `8081` | `5174` |

端口规则：

- 两个后端进程不得监听同一个端口。
- 两个前端进程不得监听同一个端口。
- 测试任务必须明确记录当前访问的是哪个环境。
- 浏览器回归测试必须指向 Pull Request 分支运行的服务。
- 如果只能运行一个实例，应先停止原始环境，再使用 Handoff 或开发 Worktree 启动修复环境。

## 11. 数据库和测试数据隔离

并行测试不仅需要隔离代码，还需要隔离测试数据。

推荐方式：

- 原始版本使用独立测试数据库或 Schema。
- Pull Request 分支使用另一个测试数据库或 Schema。
- 使用不同测试租户、测试用户或数据前缀。
- 写入型测试使用可清理的测试数据。
- 不得对生产数据库执行写入测试。
- 不得让两个任务同时修改同一批测试记录。

涉及 DataGate 安全功能时，还应确认：

- 测试账号权限符合最小权限原则。
- 查询账号、变更账号和采集账号相互隔离。
- 测试日志不包含数据库密码、Token、SQL 参数明文或结果正文。
- 审计失败时，高风险操作仍然失败关闭。

## 12. 第七步：回归验证 Pull Request

开发任务完成修复并启动 Pull Request 分支服务后，在测试任务中使用以下提示词：

```text
使用 $source-guided-system-test 回归验证 PR #456 对 Issue #123 的修复。

只测试，不修改代码。
测试目标必须是 Pull Request 分支运行的服务。
验证原始问题、相邻场景、安全规则和审计行为，并把测试结果反馈到 Pull Request。
浏览器测试优先使用 Microsoft Edge 的 Codex 浏览器扩展。
```

将 `#456` 和 `#123` 替换为实际 Pull Request 和 Issue 编号。

回归测试至少包括：

- 按 Issue 原始步骤重新测试。
- 验证预期结果已经实现。
- 验证错误码和接口响应稳定。
- 验证权限检查仍然在服务端执行。
- 验证未授权请求没有到达目标数据库。
- 验证审计记录符合项目规范。
- 验证相邻功能没有明显回归。
- 记录构建、API、浏览器和日志证据。

## 13. 回归失败处理

如果回归失败：

1. 不要合并 Pull Request。
2. 在 Pull Request 中记录失败步骤和证据。
3. 明确说明是原始缺陷未修复还是产生了新回归。
4. 将反馈发送给原开发任务继续处理。
5. 开发任务在原 Worktree 和原分支继续修改。
6. 开发任务推送新的提交。
7. 测试任务重新执行受影响场景。

开发任务继续修复的提示词：

```text
PR #456 的回归测试没有通过。

请阅读 Pull Request 中最新的测试反馈和证据，在原 Worktree 和原分支继续修复。
完成后执行相关测试并推送新的英文提交，不要创建重复 Pull Request，也不要自动合并。
```

## 14. 回归通过和合并

回归通过后：

1. 测试任务在 Pull Request 中记录通过结论和证据。
2. 检查 CI 构建和自动化测试结果。
3. 完成代码审查。
4. 确认数据库迁移和回滚方案。
5. 确认安全、性能和审计要求没有弱化。
6. 由有权限的人员合并 Pull Request。
7. 确认 GitHub Issue 已正确关闭。

在 DataGate 最终上线门禁全部通过前，只能报告已完成对应里程碑或可以进入试运行，不得表述为已经达到真实可上线状态。

## 15. Codex 推荐配置

| 职责 | Codex 环境 | 推荐 Skill | 是否修改代码 |
|---|---|---|---|
| 问题复现与系统测试 | Local | `01 源码引导系统测试` | 否 |
| Issue 评估与任务路由 | Worktree | `03 自适应任务路由` | 根据委派结果 |
| Issue 实现与 PR 创建 | Worktree | `02 GitHub Issue 实现` | 是 |
| Pull Request 回归测试 | Local 或独立验证 Worktree | `01 源码引导系统测试` | 否 |

## 16. 并行工作红线

- 禁止两个 Codex 任务同时修改同一个 Local 工作目录。
- 禁止测试任务在复现过程中顺手修复代码。
- 禁止开发任务自动合并 Pull Request。
- 禁止两个运行环境使用相同端口。
- 禁止两个写测试共享相同数据库和测试数据。
- 禁止使用未提交的代码作为正式回归测试基线。
- 禁止向 Git 提交 GitHub Token、数据库密码或其他密钥。
- 禁止使用中文 Git 提交信息。
- 禁止安装或使用 Anaconda。
- 禁止为了通过测试而弱化权限、审计、加密、只读或审批要求。

## 17. 最简操作清单

### 测试任务

```text
Local
-> 调用 01 源码引导系统测试
-> 复现问题
-> 收集证据
-> 确认缺陷
-> 创建 Issue
```

### 开发任务

```text
Worktree
-> 调用 03 自适应任务路由
-> 调用 02 GitHub Issue 实现
-> 创建 codex/ 分支
-> 修改代码
-> 测试
-> 英文 Commit
-> Push
-> 创建 PR
```

### 回归任务

```text
获取 PR 分支服务地址
-> 调用 01 源码引导系统测试
-> 验证原问题
-> 验证相邻场景
-> 记录测试证据
-> 反馈 PR
-> 审查后合并
```

