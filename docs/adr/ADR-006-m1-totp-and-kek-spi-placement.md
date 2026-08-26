# ADR-006 M1 实施偏差：KEK SPI 上移 db-core，TOTP 落于 ruoyi-system

- 状态：已确认（2026-08-26，M1 切片E）
- 背景：docs/02 将 `KekProvider` 规划在凭据保险箱（ruoyi-db-resource）内；TOTP（IAM-005）在文档中未指定落点模块。M1 切片E 实施时发现两个结构性问题需要决策。

## 决策

1. **`KekProvider` 上移至 `org.dromara.db.core.spi`（ruoyi-db-core）**。
   原 `ruoyi-db-resource` 内的 `resource.spi.KekProvider` 删除，`FileKekProvider` 实现保留在 db-resource（凭据保险箱的装配方），TOTP 密钥加密复用同一 SPI。
2. **TOTP 实现落于上游模块 `ruoyi-system`**（`org.dromara.system.totp` / `service` / `controller`），ruoyi-system 新增对 `ruoyi-db-core`、`ruoyi-db-audit` 的依赖。
   表结构仍为 DataGate 自有迁移 `V6__m1_totp.sql`（`dbg_user_totp`），不动上游 `sys_*` 表。

## 理由

- KEK 是**平台级安全原语**，不是凭据保险箱的私有物：TOTP 密钥（等价于共享秘密）同样需要 KEK 直接加密保护（AES-256-GCM，AAD 绑定 `DataGate-TOTP|{userId}`，密文跨用户搬移即解密失败）。若 SPI 留在 db-resource，则 ruoyi-system 需反向依赖资源管理模块，造成语义倒置。
- TOTP 必须与**登录链路**（`PasswordAuthStrategy`）、用户管理（`SysUser`）深度耦合：登录校验点在 ruoyi-system 的认证策略内，强行拆到独立 DataGate 模块会产生循环依赖或要求上游模块依赖下游模块——两者都违反 AGENTS.md「模块间只能通过公开 Service/SPI 调用」。
- 复用上游 `sys_user` 主键与 Sa-Token 会话，避免维护镜像用户表。

## 影响

- ruoyi-system 不再是"纯上游"模块，升级上游版本时需保留本模块内 `totp/`、`SysUserTotp*`、`PasswordAuthStrategy` 中的 M1 补丁（已集中、有迁移与测试覆盖）。
- 所有需要 KEK 的未来功能（如导出文件加密、Webhook 签名密钥）应继续使用 `db.core.spi.KekProvider`，不得各自实现密钥来源。
- `dbg_user_totp` 由 DataGate Flyway 迁移管理，保留期/清理策略随审计基线，不随上游 `sys_*` 数据。

## 已知未收口项（后续切片处理）

- 「生产环境强制 TOTP」（IAM-005  fleet-wide 强制绑定流：未绑定用户登录后仅获受限 token，只能访问绑定页）**尚未实现**；当前仅对已绑定用户强制校验。计划随 M1-01 首次改密切片一并收口（同属登录链路门禁）。
