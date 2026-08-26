# DataGate 上游版本锁定记录（M0-01）

> 本文件是版本基线的可核对记录。任何上游升级必须走独立升级任务：差异审查 → 迁移 → 回归测试 → 更新本文件。

## 1. 上游基线

| 项 | 锁定值 | 来源 |
|---|---|---|
| 后端框架 | dromara/RuoYi-Vue-Plus `v5.6.2` | commit `8136a01` |
| 前端框架 | CrazyLionCat/plus-ui `v5.6.2-v2.6.2` | commit `d0d4519` |
| JDK | Java 17（本机验证 17.0.16） | — |
| Spring Boot | 3.5.15 | 根 pom `spring-boot.version` |
| MyBatis-Plus | 3.5.16 | 根 pom |
| Sa-Token | 1.45.0 | 根 pom |
| WarmFlow | 1.8.5 | 根 pom |
| SnailJob | 1.10.0 | 根 pom |
| Redisson | 3.52.0 | 根 pom |
| SQL AST | Alibaba Druid `1.2.28`（仅 Parser/AST/Visitor） | 根 pom `druid.version` |
| 目标库连接池 | HikariCP（Spring Boot 管理） | — |
| 元数据库 | PostgreSQL（开发/测试 18.4，docker `postgres:18.4`） | — |
| 平台缓存 | Valkey 协议兼容（开发期复用 Redis 8.2 实例，许可证确认见下） | — |
| 许可证 | 上游 MIT LICENSE 保留于仓库根 | LICENSE |

## 2. 禁止事项

- 禁止从 `main`/`master`/`5.X` 移动分支构建生产版本；
- 禁止浮动依赖版本（新依赖必须在根 pom `dependencyManagement` 锁定）；
- 禁止删除上游 MIT 版权信息；
- Druid 仅使用 SQL Parser，禁止引入 Druid 连接池承载平台或目标库连接；
- 平台缓存生产默认 Valkey；改用 Redis 须先锁版本并完成许可证合规确认
  （Redis ≤7.2 BSD-3-Clause；7.4-7.8 RSALv2/SSPLv1；8.x RSALv2/SSPLv1/AGPLv3 可选）。

## 3. 已知上游风险与处置

| 风险 | 处置 | 状态 |
|---|---|---|
| RuoYi-Vue-Plus v5.6.2 工作流任务改派接口对象级授权缺失（Issue #44） | 已在 `FlwTaskServiceImpl` 增加 `checkTaskOperatorPermission`，覆盖转办/委派/加签/减签/驳回/终止 | 已修复（M0） |
| SnailJob Server、Spring Boot Admin Server 未部署 | 客户端配置 `enabled: false`，M4/M6 部署后开启 | 已关闭（M0） |
| 上游 api-decrypt 示例 RSA 密钥对 | prod 已改为环境变量注入；dev 保留本地联调用例，M1 轮换 | 部分处理（M0），M1 收口 |
| 上游默认 admin 账号（admin123） | 保留为唯一引导账号；样例账号 test/test1 已删除；M1 强制首次改密 + TOTP | 部分处理（M0），M1 收口 |

## 4. 基线验证

- `mvn clean install -DskipTests -B`：BUILD SUCCESS（2026-08-26，基线日志留存）；
- 全新机器构建步骤见 README「构建与运行」。
