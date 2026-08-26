# AGENTS.md — DataGate 数据库访问治理平台

## 1. 这个项目是干什么的

DataGate 是一套**面向公司内部全员的生产级数据库访问治理与慢查询治理平台**，对标阿里云 DMS + DAS 的核心内部治理能力，但采用开源自建（不购买商业授权）。

核心能力：

- **凭据集中托管**：数据库账号密码由平台加密保管（AES-256-GCM 信封加密，KEK 外置），员工不直接接触数据库密码；
- **按资源授权**：实例/库/Schema/表级（Redis 为实例/逻辑 DB/Key 前缀/命令类别）细粒度授权，默认拒绝、显式拒绝优先；
- **受控执行**：普通用户生产环境只允许只读查询和安全 EXPLAIN；导出、DML、DDL、Redis 写入必须独立授权并走审批流（WarmFlow）；
- **完整审计**：查询审计保存 1 年，授权/导出/变更审计保存 3 年，哈希链防篡改；
- **慢查询治理**：MySQL/PG/Redis 慢查询每分钟采集、指纹聚合、5 分钟内告警（钉钉/邮件/Webhook）、治理状态流转。

**关键定位**：不是"把 SQL 转发给 JDBC 的页面"，而是安全底座优先的治理平台。身份、密钥、审计先于查询能力实现。

## 2. 文档即唯一基线（必读）

`docs/` 目录是项目实施的**唯一需求和设计基线**（基线 1.0.0，2026-08-26）。写代码前必须按序完整阅读：

| 顺序 | 文档 | 内容 |
|---|---|---|
| 1 | 00-文档索引与项目基线 | 已确认决定、版本基线、冲突处理规则 |
| 2 | 01-产品需求规格说明书 | 用户、场景、功能与非功能需求 |
| 3 | 02-系统架构与模块详细设计 | 模块结构、执行链路、Connector SPI |
| 4 | 03-权限模型与审批流程规范 | 资源、动作、授权判定、审批 |
| 5 | 04-数据模型与数据库表设计 | 核心表、字段、索引、保留策略 |
| 6 | 05-API错误码与状态机规范 | 接口边界、幂等、分页、状态机 |
| 7 | 06-数据源适配与安全执行规范 | MySQL/PG/Redis 执行规则 |
| 8 | 07-慢查询采集分析与告警设计 | 采集、指纹、聚合、告警 |
| 9 | 08-安全审计与威胁模型 | 凭据、审计、防攻击、安全验收 |
| 10 | 09-部署监控备份恢复设计 | 部署、监控、RPO/RTO |
| 11 | 10-MVP任务拆分与验收标准 | M0–M6 里程碑、DoD、发布门禁 |
| — | 11-AI实施交接说明 | 编码边界、禁止事项、交付要求（AI 实施者必须严格遵守） |

**冲突处理优先级**：00 文档"已确认的产品决定" > 专项设计文档 > PRD > 代码既有行为。不得以"框架原本如此"覆盖安全要求。

**需求编号**：接口、迁移、测试、验收用例必须引用需求编号（IAM/RES/CRED/AUTH/WF/QRY/REDIS/EXP/CHG/MASK/AUD/SLOW/ALT/OPS）。

## 3. 锁定的技术基线（不得浮动）

| 项 | 版本 |
|---|---|
| 后端 | RuoYi-Vue-Plus `v5.6.2`（commit `8136a01`），不从 5.X 移动分支构建 |
| 前端 | plus-ui `v5.6.2-v2.6.2`（commit `d0d4519`） |
| JDK | Java 17 |
| Spring Boot | 3.5.15 |
| 元数据库 | PostgreSQL |
| 平台缓存/锁 | Valkey（BSD-3-Clause），Redisson 客户端；改用 Redis 须先锁版本并做许可证确认 |
| SQL AST | Alibaba Druid SQL Parser（仅用 Parser/AST/Visitor，不用 Druid 连接池，WallFilter 不作最终授权） |
| 目标库连接池 | HikariCP（按数据源/凭据用途建小池，**禁止**注册进 dynamic-datasource） |
| P0 支持引擎 | MySQL（含 RDS/PolarDB）、PostgreSQL（含 RDS）、Redis/Tair |
| 审批流 | WarmFlow；任务调度 SnailJob；认证 Sa-Token |

上游已知风险：v5.6.2 工作流任务改派接口存在对象级授权缺失报告（Issue #44），实施前必须核对源码，未修复先禁用，覆盖转办/加签/减签/撤回等相邻接口。

## 4. 仓库结构

```text
DataGate/
├─ docs/                  # 需求与设计基线（唯一权威，见第 2 节）
├─ pom.xml                # 父 POM，groupId=org.dromara，版本由 revision 属性管理
├─ ruoyi-admin/           # 启动模块（org.dromara.DromaraApplication），装配所有模块
├─ ruoyi-common/          # 上游公共模块（satoken/redis/mybatis/加密/脱敏等）
├─ ruoyi-extend/          # 上游扩展（监控-admin、snailjob 等）
├─ ruoyi-modules/         # 业务模块
│  ├─ ruoyi-system/       # 上游：用户/部门/岗位/角色/菜单/登录
│  ├─ ruoyi-workflow/     # 上游：WarmFlow 审批（M0 已删除请假演示、已修复任务改派对象级授权 Issue #44）
│  ├─ ruoyi-job/          # 上游：SnailJob 调度（M0 已删除演示任务，客户端默认关闭）
│  ├─ ruoyi-generator/    # 上游：代码生成（M0 起不再装配进 ruoyi-admin，仅保留源码）
│  ├─ ruoyi-demo/         # 上游演示（M0 起从构建与装配中移除，仅保留源码）
│  ├─ ruoyi-db-core/      # 【M0 已建】公共领域类型 + Connector SPI + 统一错误码（无持久化依赖）
│  ├─ ruoyi-db-resource/  # 【骨架】数据源、资源目录、凭据保险箱、元数据同步（M1 实现）
│  ├─ ruoyi-db-auth/      # 【骨架】外部资源权限与判定（M2 实现）
│  ├─ ruoyi-db-workflow/  # 【骨架】权限/导出/变更工单领域流程（M2/M5 实现）
│  ├─ ruoyi-db-console/   # 【骨架】工作台会话与执行编排（M2 实现）
│  ├─ ruoyi-db-executor/  # 【骨架】查询/Redis/导出/变更执行网关（M2 实现）
│  ├─ ruoyi-db-audit/     # 【骨架】专项不可变审计（M1 实现）
│  ├─ ruoyi-db-observability/ # 【骨架】慢查询采集、指纹、聚合、治理（M4 实现）
│  ├─ ruoyi-db-alert/     # 【骨架】告警规则、事件、通知通道 SPI（M4 实现）
│  └─ ruoyi-db-connectors/    # 【骨架】connector-mysql（M2）/connector-postgresql/connector-redis（M3）/connector-aliyun
├─ plus-ui/               # 前端（Vue3 + TS + ElementPlus + Vite）
└─ script/                # bin/docker/sql 脚本
```

**DataGate 后端模块**（02 文档定义，`ruoyi-modules/` 下，由 ruoyi-admin 统一装配为单体；M0 已建骨架）：

- `ruoyi-db-core`：公共领域类型 + Connector SPI（无持久化依赖，无 Mapper/Controller）
- `ruoyi-db-resource`：数据源、资源目录、凭据保险箱、元数据同步
- `ruoyi-db-auth`：外部资源权限与判定（Allow/Deny/继承/有效期/版本）
- `ruoyi-db-workflow`：权限/导出/变更工单领域流程（适配 WarmFlow）
- `ruoyi-db-console`：工作台会话与执行编排
- `ruoyi-db-executor`：查询/Redis/导出/变更执行网关（独立连接池）
- `ruoyi-db-audit`：专项不可变审计（追加写、哈希链、归档）
- `ruoyi-db-observability`：慢查询采集、指纹、聚合、治理
- `ruoyi-db-alert`：告警规则、事件、通知通道 SPI
- `ruoyi-db-connectors/`：`connector-mysql`、`connector-postgresql`、`connector-redis`、`connector-aliyun`

模块间只能通过公开 Service/SPI 或领域事件调用，禁止跨模块直接注入 Mapper。

**前端约定**：页面 `plus-ui/src/views/db/`，接口 `plus-ui/src/api/db/`，类型定义放对应 `types.ts`。

## 5. 构建与运行

```powershell
# 后端构建（默认跳过测试，见父 pom skipTests）
mvn clean install
mvn clean package -P local        # 指定环境 profile: local/dev/prod

# 后端运行：ruoyi-admin 模块，主类 org.dromara.DromaraApplication

# 前端（要求 Node >= 20.19，npm >= 8.19）
cd plus-ui
npm install
npm run dev                       # 开发
npm run build:prod                # 生产构建
npm run lint:eslint               # 代码检查
```

> 注意：本机工作目录路径含中文，**Git Bash 下 npm 会因中文路径失败**；如需临时 npm 操作，在 ASCII 路径（如 `C:\Users\cxy784853792\tmp-import`）下进行，Node 脚本本身读写中文路径没问题。

**依赖环境**：VM `192.168.149.128` 提供 MySQL 8.4（3306，root/mrlu）、PostgreSQL 18（5432，postgres/mrlu）、Redis 8.2（6379，密码 mrlu）、RabbitMQ、MinIO（9000/9001，mrlu/mrlumrlu）。SSH：`mrlu/mrlu`，可用 `/tmp/ssh-mrlu.sh` 封装脚本。本地 Windows 无 `gh`，GitHub 操作在 VM 上执行。

## 6. 编码铁律（来自 docs/11，必须遵守）

**架构边界**：

- 复用 RuoYi：用户/部门/角色/菜单、Sa-Token 会话、MyBatis-Plus、Redisson、WarmFlow、SnailJob、通用 CRUD；
- 必须独立实现：外部资源授权、凭据保险箱、SQL/Redis 解析与受控执行、查询审计、任意结果集列级脱敏、慢查询管道、通知 SPI、审计哈希链；
- 领域模型不依赖 Controller DTO；连接器只通过 SPI 被业务层调用；禁止在 Controller 拼 SQL 或碰 JDBC；
- 状态迁移只经领域服务；时间统一 Instant/UTC；唯一约束作为并发最后防线；
- 异常对外转换为 05 文档的稳定错误码。

**禁止的捷径（红线）**：

- ❌ 用 RuoYi 数据权限替代目标库资源授权；用 dynamic-datasource 承载用户 SQL；用 p6spy/操作日志替代查询审计；
- ❌ 用正则/关键字替代方言 AST；SQL 解析失败后交给数据库试运行；先执行再判断权限；
- ❌ 明文密码进 application.yml/环境模板/数据库；提供查看密码的接口；凭据表单回填；
- ❌ 前端按钮隐藏替代服务端鉴权；前端脱敏替代服务端流式脱敏；
- ❌ 查询结果写入审计/日志/Redis；导出复用 SELECT 权限；审批后允许编辑 SQL；
- ❌ 一个数据库账号同时用于查询/变更/采集；Redis 开放 KEYS/EVAL/MONITOR/CONFIG；PG 默认开放 EXPLAIN ANALYZE；
- ❌ 日志记录 SQL 参数明文/结果正文——只记 ID、fingerprint、耗时、错误码；
- ❌ Secret 类型的 `toString()` 输出真值；敏感 DTO 用 Lombok 全字段 toString。

**安全语料**：06/08 文档的攻击语料要做成版本化测试数据集，解析器/驱动/连接器每次升级必须全量回归（拒绝的请求确认未到达目标库、未知语法失败关闭）。

**其他**：

- 严禁安装/使用 Anaconda；Python 只能作为辅助，不取代可审计的构建/迁移流程；
- 多租户：关闭或固定系统租户，前端不展示租户入口，不删除上游租户代码；
- 资源树等权限数据由后端过滤，不在前端下载全量再隐藏；
- 审计写入故障时高风险业务动作必须失败关闭。

## 7. 实施节奏（M0–M6）

严格按 docs/10 执行，每个里程碑纵向切片交付（迁移→领域接口/状态机→测试→持久化/服务→API/前端→真实引擎集成测试→审计/指标/错误码→验收证据），**不要一次性生成大量未经构建的文件**，每个切片完成后编译、测试、检查 git diff：

| 里程碑 | 内容 | 出口条件 |
|---|---|---|
| M0 | 基线固化、模块骨架、Flyway、CI/SBOM、关闭演示/注册入口、ADR-001~005 | 可重复构建，无上游样例暴露 |
| M1 | 身份强化（TOTP）、凭据保险箱、数据源管理、元数据同步、审计设施 | 凭据不回显，审计篡改可检测 |
| M2 | 授权引擎、审批、MySQL 连接器、查询控制台、查询审计 | 恶意语料零写入，撤权 60s 生效 |
| M3 | PostgreSQL、Redis/Tair 连接器 | 三引擎安全/一致性测试通过 |
| M4 | 慢查询采集、聚合、告警、治理工作台 | 5 分钟告警，百万事件/日压测通过 |
| M5 | 导出、DML/DDL/Redis 变更工单、紧急访问、字段级脱敏 | 幂等、复核、审计通过 |
| M6 | HA、监控、备份恢复、性能、安全验收 | 上线门禁全过，小流量灰度完成 |

M1 首个纵向切片：管理员建用户→首次改密/TOTP→添加 MySQL 数据源→凭据只写不可读回→连接测试→元数据同步→全程审计→篡改审计可被发现（不执行用户 SQL）。

## 8. 交付汇报格式

每次交付报告须包含（禁止只说"已完成/理论可用"）：

1. 完成的需求 ID 和里程碑；
2. 新增/修改文件；
3. 数据库迁移版本；
4. 执行的构建、测试和结果（贴输出证据）；
5. 安全/性能/恢复证据；
6. 与规格的偏差和 ADR；
7. 未完成项与风险；
8. 下一步最小安全切片。

**"真实可上线"的唯一标准**：docs/10 的 13 个最终验收场景全部通过 + 安全/容量/恢复门禁满足。在此之前只能报告"已完成某里程碑/可进入试运行"。

## 9. 何时必须停下来问用户

- 需要访问真实生产系统、凭据或公司网络；
- 要改变已确认的支持引擎、审批链、审计保留期；
- 要弱化加密、审计、只读、TOTP、双人审批或灾备目标；
- 需要购买商业服务或使用有许可证风险（GPL/AGPL/SSPL/RSAL）的组件；
- 上游精确 tag 不存在或存在重大不兼容；
- 需要破坏性修改用户已有代码/数据。

低风险实现细节可自行决定，但须满足：失败关闭、最小权限、可审计，并在 ADR 或 `assumptions.md` 记录。
