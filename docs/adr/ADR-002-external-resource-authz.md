# ADR-002 为什么外部资源授权不复用 RuoYi 数据权限

- 状态：已确认（2026-08-26，M0）
- 背景：RuoYi 自带基于 `sys_role` + 数据范围（dept/user）的行级数据权限，作用于平台自身 SQL 查询。

## 决策

外部数据源资源授权由独立模块 `ruoyi-db-auth` 实现（Grant = Effect + Subject + Resource + ActionSet + Conditions + Validity + Source），不复用 RuoYi 数据权限。

## 理由

- 语义不同：RuoYi 数据权限是「平台内部表的行过滤」，DataGate 需要「外部库/Schema/表/Redis Key 前缀/命令类别」的动作级授权；
- 判定模型不同：需要默认拒绝、显式拒绝优先、细粒度优先、有效期、条件合并（docs/03 第 7 节），上游不支持；
- 可解释与可审计：每次判定需要 decisionId、命中规则与限制快照，供审计与排障；
- 上游数据权限改动会污染业务授权语义，合并升级风险高。

## 影响

- RuoYi 数据权限仅继续保护平台内部业务数据（docs/02 第 2.4 节）；
- 授权判定输入只接受规范化资源 ID，不接受未校验的资源名称字符串；
- 权限缓存键必须包含 policyVersion，撤销 60 秒内生效（docs/10 M2 出口条件）。
