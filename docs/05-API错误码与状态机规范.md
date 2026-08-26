# API、错误码与状态机规范

## 1. API 基础约定

### 1.1 路径和版本

- 业务 API 前缀：`/api/db/v1`。
- 保留 RuoYi-Vue-Plus 既有系统管理接口风格，不重写上游接口。
- 破坏性变更必须升级 API 大版本；新增可选字段不升级。
- 接口名使用资源名词；状态动作使用 `:action` 或明确子资源，项目内保持统一。

### 1.2 身份认证

- 使用 Sa-Token 现有认证机制。
- 每个接口声明 RuoYi 功能权限。
- 涉及外部资源时，Controller 不直接判定数据权限，由领域服务调用 `AuthorizationService`。
- 高危接口要求 TOTP 和最近 5 分钟二次认证标记。

### 1.3 请求追踪

- 所有响应返回 `traceId`。
- 客户端可传 `X-Request-Id`；服务端验证格式后采用，否则生成。
- 写操作支持 `Idempotency-Key`，有效期至少 24 小时。
- 同一幂等键、同一用户和同一路径但请求体不同返回冲突。

### 1.4 响应

复用 RuoYi-Vue-Plus 5.X 的统一响应对象和分页对象，不再创建第二种顶层 envelope。错误响应至少包含：

```json
{
  "code": 42001,
  "msg": "当前操作未获得资源授权",
  "data": {
    "errorCode": "AUTH_RESOURCE_DENIED",
    "traceId": "...",
    "retryable": false
  }
}
```

生产响应不返回数据库堆栈、JDBC URL、数据库用户名、SQL 参数或内部类名。

### 1.5 分页

- 普通配置和资源列表复用 `PageQuery`/`TableDataInfo`。
- 审计、慢查询等大表分页使用稳定排序和游标分页优先，不允许深度 `OFFSET` 无限制扫描。
- 默认 20 条，普通最大 200 条；导出不复用列表分页接口。

## 2. 核心 API

以下为逻辑契约；实现时包名、Controller 名和响应包装遵循上游代码风格。

### 2.1 环境与数据源

| 方法 | 路径 | 功能权限 | 说明 |
|---|---|---|---|
| GET | `/environments` | `db:resource:list` | 环境列表 |
| POST | `/environments` | `db:resource:add` | 新建环境 |
| GET | `/data-sources` | `db:resource:list` | 只返回可管理/发现的数据源 |
| POST | `/data-sources` | `db:resource:add` | 创建草稿数据源 |
| GET | `/data-sources/{id}` | `db:resource:query` | 不返回凭据秘密 |
| PUT | `/data-sources/{id}` | `db:resource:edit` | 乐观锁更新非秘密配置 |
| POST | `/data-sources/{id}:verify` | `db:resource:verify` | 验证当前凭据和能力 |
| POST | `/data-sources/{id}:enable` | `db:resource:edit` | 启用 |
| POST | `/data-sources/{id}:disable` | `db:resource:edit` | 禁用并阻止新执行 |
| POST | `/data-sources/{id}/metadata-sync-jobs` | `db:resource:sync` | 触发同步 |
| GET | `/data-sources/{id}/resources` | `db:resource:list` | 返回过滤后的资源树 |

创建数据源请求中的密码字段只用于创建凭据版本；Controller 日志和校验异常不得打印请求体。

### 2.2 凭据

| 方法 | 路径 | 功能权限 | 说明 |
|---|---|---|---|
| GET | `/data-sources/{id}/credentials` | `db:credential:list` | 仅元信息 |
| POST | `/data-sources/{id}/credentials` | `db:credential:add` | 新建用途凭据 |
| POST | `/credentials/{id}/versions` | `db:credential:rotate` | 创建并验证新版本 |
| POST | `/credentials/{id}/versions/{versionId}:activate` | `db:credential:rotate` | 原子切换 |
| POST | `/credentials/{id}:verify` | `db:credential:verify` | 验证有效性 |
| POST | `/credentials/{id}:disable` | `db:credential:disable` | 禁用并关闭相关池 |

没有任何读取密码、导出密码或复制连接字符串接口。

### 2.3 授权

| 方法 | 路径 | 功能权限 | 说明 |
|---|---|---|---|
| GET | `/grants` | `db:auth:list` | 管理员按权限范围查看 |
| POST | `/grants` | `db:auth:grant` | 直接授权，生产需高权限 |
| POST | `/grants/{id}:revoke` | `db:auth:revoke` | 回收 |
| POST | `/authz:check` | 内部权限 | 批量权限判定，禁止普通前端任意枚举 |
| GET | `/users/{userId}/effective-permissions` | `db:auth:explain` | 最终权限解释 |
| GET | `/me/effective-permissions` | 登录用户 | 本人权限 |

`POST /authz:check` 对外只允许检查调用者本人和已知资源；内部服务使用独立 Java 接口，避免通过 HTTP 绕行。

### 2.4 权限申请与审批

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/access-requests` | 创建草稿 |
| PUT | `/access-requests/{id}` | 草稿修改，需版本号 |
| POST | `/access-requests/{id}:submit` | 提交并启动 WarmFlow |
| POST | `/access-requests/{id}:cancel` | 申请人撤回待审批请求 |
| GET | `/access-requests/{id}` | 按参与关系查看 |
| GET | `/access-requests/my` | 本人申请 |
| GET | `/approval-tasks/my` | 本人待办 |
| POST | `/approval-tasks/{id}:approve` | 审批通过，服务端校验非本人 |
| POST | `/approval-tasks/{id}:reject` | 审批拒绝 |

审批接口必须使用幂等键和任务版本；重复点击只产生一次状态变化。

### 2.5 SQL 查询

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/queries:parse` | 解析、资源提取和规则预览，不执行 |
| POST | `/queries:execute` | 同步受控查询 |
| DELETE | `/queries/{executionNo}` | 取消运行中的查询 |
| GET | `/query-executions/{executionNo}` | 只返回执行元数据，不返回历史结果 |
| GET | `/query-executions` | 本人历史或审计查询 |

执行请求：

```json
{
  "clientExecutionId": "uuid",
  "dataSourceId": "123",
  "database": "app_db",
  "schema": "public",
  "statement": "select ...",
  "requestedMaxRows": 500
}
```

执行响应包含列定义、脱敏标志、行数据、截断标志和执行元数据。服务端硬限制优先，客户端不能指定超时扩大权限。

取消机制：

- 客户端生成唯一 `clientExecutionId`。
- 执行节点在 Redis 注册 `executionNo → nodeId`。
- DELETE 请求发布取消命令至对应节点。
- 浏览器中止 HTTP 请求时也尝试取消 Statement。
- 取消幂等；已结束查询返回当前最终状态。

### 2.6 Redis/Tair

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/redis/keys:scan` | 按授权前缀 SCAN |
| GET | `/redis/keys/{encodedKey}/metadata` | 类型、TTL、大小 |
| GET | `/redis/keys/{encodedKey}/value` | 受限读取 |
| POST | `/redis/commands:validate` | 命令解析和权限预览 |
| POST | `/redis/commands:execute` | 仅安全读命令；写命令拒绝或转工单 |

Key 使用 URL-safe 编码并在服务端解码验证；不能因为路径解析差异绕过前缀匹配。

### 2.7 导出

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/export-requests` | 基于查询指纹创建申请 |
| GET | `/export-jobs/{id}` | 查询状态 |
| POST | `/export-jobs/{id}:cancel` | 未完成任务取消 |
| POST | `/export-jobs/{id}:download-ticket` | 二次认证后生成一次性下载票据 |
| GET | `/export-downloads/{ticket}` | 下载并计次 |

不返回永久对象存储 URL；下载票据默认 5 分钟有效且使用一次。

### 2.8 变更

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/change-orders` | 创建 DML/DDL/Redis 写入草稿 |
| POST | `/change-orders/{id}:precheck` | 解析、影响和风险检查 |
| POST | `/change-orders/{id}:submit` | 启动审批 |
| POST | `/change-orders/{id}:schedule` | DBA 设置执行窗口 |
| POST | `/change-orders/{id}:execute` | 到达窗口且二次认证后执行 |
| POST | `/change-orders/{id}:cancel` | 允许状态下取消 |
| GET | `/change-orders/{id}/executions` | 尝试历史 |

### 2.9 慢查询与告警

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/slow-query-fingerprints` | 指纹列表和聚合指标 |
| GET | `/slow-query-fingerprints/{id}` | 详情、趋势和权限过滤后的样例 |
| POST | `/slow-query-fingerprints/{id}:claim` | 认领 |
| POST | `/slow-query-fingerprints/{id}:transition` | 治理状态变化 |
| POST | `/slow-query-fingerprints/{id}/comments` | 添加治理备注 |
| GET | `/slow-collectors` | 采集状态 |
| POST | `/slow-collectors/{id}:run` | 手动触发，幂等 |
| GET/POST/PUT | `/alert-rules` | 告警规则管理 |
| POST | `/alert-rules/{id}:test` | 用历史/模拟数据测试 |
| GET | `/alert-events` | 告警事件 |
| POST | `/alert-events/{id}:acknowledge` | 确认 |
| POST | `/alert-events/{id}:silence` | 有期限静默 |
| GET/POST/PUT | `/notification-channels` | 通道管理，秘密不回显 |
| POST | `/notification-channels/{id}:test` | 测试投递 |

### 2.10 审计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/audit-events` | 审计员检索，游标分页 |
| GET | `/audit-events/{eventId}` | 查看单事件 |
| POST | `/audit-reports` | 异步生成受控报告 |
| GET | `/audit-reports/{id}` | 报告状态 |
| POST | `/audit-archives/{id}:verify` | 验证归档哈希 |

没有 PUT/PATCH/DELETE 审计事件接口。

## 3. 错误码

### 3.1 HTTP 状态

| HTTP | 使用场景 |
|---|---|
| 400 | 格式、解析、业务前置条件错误 |
| 401 | 未登录、Token 失效、二次认证失效 |
| 403 | 功能或资源权限不足 |
| 404 | 管理员查询确实不存在；普通用户资源不存在/无权可统一处理 |
| 409 | 幂等冲突、版本冲突、非法状态迁移 |
| 410 | 下载票据或临时资源已过期 |
| 422 | SQL/命令可解析但不符合安全规则 |
| 429 | 并发、速率或队列限制 |
| 500 | 平台内部错误，隐藏实现细节 |
| 502/503 | 数据源不可用或平台依赖不可用 |
| 504 | 查询或外部调用超时 |

### 3.2 业务错误码

| 数字范围 | 前缀 | 示例 |
|---|---|---|
| 41000-41999 | IAM | `IAM_MFA_REQUIRED`、`IAM_REAUTH_REQUIRED` |
| 42000-42999 | AUTH | `AUTH_RESOURCE_DENIED`、`AUTH_GRANT_EXPIRED` |
| 43000-43999 | RESOURCE | `RESOURCE_DISABLED`、`RESOURCE_CAPABILITY_UNSUPPORTED` |
| 44000-44999 | CREDENTIAL | `CREDENTIAL_INVALID`、`CREDENTIAL_ROTATION_CONFLICT` |
| 45000-45999 | QUERY | `QUERY_PARSE_FAILED`、`QUERY_UNSAFE_STATEMENT`、`QUERY_LIMIT_EXCEEDED` |
| 46000-46999 | REDIS | `REDIS_COMMAND_DENIED`、`REDIS_KEY_PREFIX_DENIED` |
| 47000-47999 | WORKFLOW | `WORKFLOW_SELF_APPROVAL_DENIED`、`WORKFLOW_STATE_CONFLICT` |
| 48000-48999 | EXPORT/CHANGE | `EXPORT_LIMIT_EXCEEDED`、`CHANGE_PRECHECK_FAILED` |
| 49000-49999 | SLOW/ALERT | `COLLECTOR_CURSOR_CONFLICT`、`NOTIFICATION_DELIVERY_FAILED` |

## 4. 状态机

### 4.1 数据源

```text
DRAFT → VERIFYING → ACTIVE
  │         └────→ ERROR
  └──────────────→ ARCHIVED
ACTIVE → DISABLED → ACTIVE
ACTIVE/DISABLED/ERROR → ARCHIVED
```

- 只有验证成功才能 ACTIVE。
- ARCHIVED 不可恢复为 ACTIVE；重新接入创建新数据源。

### 4.2 凭据版本

```text
PENDING → VERIFIED → ACTIVE → RETIRED
    └────→ INVALID
VERIFIED └──────────→ INVALID
```

同一凭据同时只能有一个 ACTIVE 版本。

### 4.3 查询执行

```text
QUEUED → RUNNING → SUCCEEDED
   │        ├────→ FAILED
   │        ├────→ CANCELED
   │        ├────→ TIMED_OUT
   │        └────→ UNKNOWN
   ├─────────────→ REJECTED
   └─────────────→ CANCELED
```

终态不可修改。UNKNOWN 仅用于平台无法确认数据库端结果的异常场景，不得自动改为成功。

### 4.4 导出

```text
DRAFT → PENDING_APPROVAL → APPROVED → QUEUED → RUNNING → SUCCEEDED → EXPIRED → DELETED
                    ├→ REJECTED          ├→ FAILED
                    └→ CANCELED          └→ CANCELED
```

### 4.5 变更

```text
DRAFT → PRECHECKING → PRECHECKED → PENDING_APPROVAL → APPROVED
             └→ PRECHECK_FAILED          ├→ REJECTED
APPROVED → SCHEDULED → RUNNING → SUCCEEDED
                           ├→ FAILED
                           └→ UNKNOWN
```

变更 SQL 内容变化后必须回到 DRAFT 并清空原审批结论。

### 4.6 慢查询治理

```text
DISCOVERED → CLAIMED → IN_PROGRESS → PENDING_VERIFY → RESOLVED
    │           │            │                 └→ IN_PROGRESS
    └───────────┴────────────┴───────────────→ IGNORED
IGNORED → DISCOVERED（再次严重恶化或人工恢复）
```

### 4.7 告警事件

```text
PENDING → FIRING → ACKNOWLEDGED → RESOLVED
              ├→ SILENCED → FIRING/RESOLVED
              └──────────→ RESOLVED
```

## 5. 并发和幂等

- 所有状态迁移请求携带 `version`，使用乐观锁更新。
- WarmFlow 回调、SnailJob 重试和消息重投以领域幂等键去重。
- 凭据激活使用数据库事务和行锁保证唯一 ACTIVE。
- 查询执行的 `clientExecutionId` 在用户范围内唯一。
- 导出、变更执行不会因 HTTP 超时自动重试。
- 通知投递可以重试，但每次尝试单独记录。

## 6. API 安全测试

必须覆盖：

- 修改路径 ID 访问他人资源。
- 使用有菜单权但无数据权的账号调用接口。
- 重放审批、导出和变更请求。
- 在 JSON、查询参数、文件名和 Header 中注入秘密或超长输入。
- 利用错误响应枚举无权数据源。
- 利用并发请求重复审批或重复执行变更。
- 利用过期 ExecutionPlan、过期下载票据和旧权限缓存。
- 取消查询时跨节点路由和幂等行为。
