# M1 检查点报告（切片 A–E，2026-08-26）

里程碑：M1 安全底座与数据源资产（docs/10 第 5 节）。本报告覆盖 M1 前五个纵向切片，遵循 AGENTS.md 第 8 节交付格式。

## 1. 完成的需求 ID

| 需求 | 内容 | 状态 |
|---|---|---|
| AUD-001/002 系 | 统一 AuditEvent、追加写、哈希链、失败关闭（appendIsolated REQUIRES_NEW） | ✅ 切片A |
| CRED-001/002 | AES-256-GCM 信封加密、KEK Provider SPI（文件挂载实现） | ✅ 切片A |
| RES-001/002/003 | 数据源结构化配置、环境/状态、SSRF 网络校验 | ✅ 切片B/C |
| CRED-003 | 凭据只写 API（无回显、覆盖式新版本）、用途（QUERY/CHANGE/MONITOR） | ✅ 切片C |
| RES-004 | 连接测试（返回分项能力，不泄露底层异常） | ✅ 切片C |
| RES-005 | MySQL 元数据同步（库/表/视图/列 → dbg_resource，DROPPED 标记） | ✅ 切片D |
| IAM-005 | TOTP（RFC 6238）：绑定/确认/登录强制/恢复码/管理员重置 | ✅ 切片E（fleet-wide 强制绑定流未收口，见 ADR-006） |

## 2. 新增/修改文件（按提交）

- `b21302a` 切片A（22 文件）：V4 迁移（dbg_audit_event + 触发器、dbg_credential*）、AuditService/AuditHashChain、CredentialCryptoService、KekProvider/FileKekProvider、AuditChainIntegrationTest；
- `6cc4e0c` 切片B（24 文件）：V5 迁移（dbg_environment/dbg_data_source/dbg_resource/dbg_metadata_sync_job）、数据源与凭据领域/Mapper/服务、ConnectorRegistry、NetworkAddressValidator、MysqlConnector 骨架、ConnectionProfile +username；
- `42607a3` 切片C（17 文件）：DbDataSourceController / DbCredentialController / DbEnvironmentController、VO、DbExceptionHandler（统一错误码→HTTP 状态）、connectionOptions 白名单序列化、NetworkAddressValidatorTest；
- `903f83e` 修复：DbDataSourceBo 补 @AutoMapper，connectionOptions 由服务层序列化；
- `cc7051e` 切片D（12 文件）：ResourceNode、MetadataProvider.fetchCatalog SPI、MysqlMetadataProvider 完整实现（information_schema 只读）、DbResource/DbMetadataSyncJob、MetadataSyncServiceImpl（分事务、DNS 复核、凭据用途回退、状态门禁）、同步/资源查询端点；
- `074f0cf` 切片E（17 文件）：V6 迁移（dbg_user_totp）、KekProvider 上移 db-core、TotpSupport/TotpSecretCipher、SysUserTotpService/Controller、PasswordLoginBody +mfaCode、PasswordAuthStrategy 接入、TotpSupportTest。

## 3. 数据库迁移版本

`flyway_schema_history`：V1/V2（上游基线）、V3（M0 收口）、V4（m1_audit_and_credential）、V5（m1_datasource_and_metadata）、V6（m1_totp）——全部 success。

## 4. 构建、测试与结果

- 全量 `mvn install` 通过（本地仓库 `D:\apache-maven-3.9.8-bin\MAVEN—local repository`）；
- 单元测试 23 个全部通过（`-DskipTests=false -DtestTags=unit`）：db-core 5、db-audit 3、db-resource 11、ruoyi-system（TOTP）4；
- 集成测试 `AuditChainIntegrationTest` 通过（哈希链追加/篡改检测/触发器拒绝 UPDATE/DELETE）；
- E2E（脚本驱动 8081 实例，dev 环境真实 PostgreSQL + VM MySQL 8.4.11）：
  - 加密登录（@ApiEncrypt RSA+AES 客户端）、环境列表、建数据源、SSRF 拦截（43005）、凭据只写无泄漏、连接测试通过；
  - 元数据同步：首次 found=13729（v1），二次幂等 found=0/updated=13729（v2），库→表→列三层展开正确；
  - TOTP 11 步全 PASS：setup→confirm→无码/错码 41001 拒绝→正确码放行→同码重放拒绝→恢复码一次性→管理员重置；
  - 审计链完整，含 DENIED 事件。

## 5. 安全证据

- 凭据：无任何读回 API；日志/异常不含明文；GCM 认证加密；
- TOTP 密钥：KEK 直接 AES-GCM，AAD=`DataGate-TOTP|{userId}`，跨用户搬移即解密失败；密钥与恢复码仅 setup 时展示一次；时间步防重放（last_step）；
- SSRF：NetworkAddressValidator 7 组用例（环回/内网/链路本地/保留段/端口白名单）；
- 审计：`dbg_audit_event` 触发器禁 UPDATE/DELETE，链键=UTC 日，篡改可被链校验发现。

## 6. 与规格的偏差

- 见 `docs/adr/ADR-006`：KekProvider 上移 db-core；TOTP 落于 ruoyi-system（上游模块扩展）。
- 元数据同步当前为**手工触发**（POST /db/datasource/{id}/sync），定时同步与单源锁未实现（M1-04 剩余）。
- 「缺失三次标记 DROPPED」当前实现为**当次消失即标记 DROPPED**，三次确认窗口未实现（M1-04 剩余）。

## 7. 未完成项与风险

- M1-01 剩余：首次登录强制改密（IAM-002）、密码策略（IAM-003）；锁定/会话管理/登录频率限制部分上游已有，待核对；
- M1-02 剩余：双版本轮换 + 连接池排空、金丝雀 Secret 泄漏测试；
- M1-04 剩余：定时同步、单源锁、三次消失确认；
- M1-05 剩余：审计查询只读 API、字段脱敏、归档校验命令；
- 前端：数据源/凭据/TOTP 页面（plus-ui `views/db`、`api/db`）未开始；
- 风险：TOTP 强制绑定流未收口前，未绑定用户仍可仅凭密码登录（dev 可接受，prod 门禁项）。

## 8. 下一步最小安全切片

切片F：首次登录强制改密（IAM-002）+ 密码策略（IAM-003：≥12 位、≥3 类字符、不含账号信息），含单元测试与 E2E；随后 M1-04 定时同步/单源锁。
