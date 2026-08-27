# M2-02 检查点报告（WarmFlow 查询权限审批流程装配，2026-08-27）

里程碑：M2-02（docs/03 §10.1、docs/10 M2-02）。本报告覆盖 WarmFlow 审批流装配：流程定义种子 → 申请起流程 → 审批办理 → 回调生成 Grant，遵循 AGENTS.md 第 8 节交付格式。

## 1. 完成的需求 ID

| 需求 | 内容 | 状态 |
|---|---|---|
| WF-001 | 查询权限申请单 + 审批流程装配（申请→审批→批准生成 Grant / 拒绝不生成 / 幂等） | ✅ 端到端验证通过 |
| WF（流程定义） | dbg_query_grant 流程定义（开始→申请人→审批人→结束）V10 种子 | ✅ |
| WF（回调接通） | finish 钩子经 ProcessEvent 事件 → GrantApprovalEventListener → onApproval 生成 Grant | ✅ |
| AUTH（写侧复用） | GrantAdminService.createGrant(sourceType=REQUEST, sourceId=申请单) + policy_version 递增 + 缓存失效广播 | ✅ M2-02 复用 |
| docs/03 §13 #9 | 申请人不能审批本人申请（服务端强制） | ✅ 端到端验证 |
| docs/03 §13 #2 | 申请人提交后流转到审批节点，PASS:approve 锁定目标审批人 | ✅ |

## 2. 新增/修改文件

**迁移**：
- `ruoyi-admin/src/main/resources/db/migration/V10__m2_query_grant_flow.sql` — dbg_query_grant 流程定义种子（flow_definition id=9001 + flow_node 9002-9005 + flow_skip 9006-9008；开始→申请人(第一个中间节点,applyNodeCode 识别)→审批人(approve,permissionFlag 留空运行时由 PASS:approve 变量锁定)→结束）。

**db-workflow 主代码**：
- `constant/DbWorkflowConstants.java` — FLOW_CODE_QUERY_GRANT/NODE_APPROVE/VAR_APPROVE_NODE/状态常量。
- `domain/bo/GrantApplyBo.java` — 申请入参（目标审批人/主体/资源/动作/效果/条件/时效/理由）。
- `domain/bo/GrantApproveBo.java` — 审批入参（applicationId/message）。
- `domain/vo/GrantApplicationVo.java` — 申请单视图。
- `mapper/FlowTaskQueryMapper.java` — 轻量 @Select 查 flow_task 待办 taskId（不引入 warm-flow 依赖）。
- `repository/GrantApplicationRepository.java` — 加 updateFlowInstanceId/updateStatus/page。
- `repository/impl/GrantApplicationRepositoryImpl.java` — 实现。
- `service/GrantApplicationService.java` — 接口（apply/approve/reject/cancel/pageList）。
- `service/impl/GrantApplicationServiceImpl.java` — 实现（apply:startCompleteTask 启动+办理申请人节点+回填；approve:completeTask 触发 finish→监听器 onApproval；reject/cancel:deleteInstance+updateStatus+主动 onRejection；pageList）。
- `listener/GrantApprovalEventListener.java` — @EventListener 监听 ProcessEvent(flowCode 匹配 + FINISH→onApproval / TERMINATION→onRejection)。
- `controller/DbWorkflowController.java` — REST（POST apply/approve/reject/cancel + GET list）。
- `pom.xml` — 加 ruoyi-common-satoken（LoginHelper）+ ruoyi-common-core（WorkflowService）+ spring-boot-starter-test（mockito mockStatic）。

**测试**：
- `GrantApplicationServiceImplTest.java`（新）— 3 单测（apply 自批拒绝 / approve 申请人自批拒绝 / approve 非指定审批人拒绝），MockedStatic LoginHelper。
- `GrantApprovalCallbackServiceImplTest.java`（修）— 桩补全新接口方法（page/updateFlowInstanceId/updateStatus）。

## 3. 数据库迁移版本

V10（dbg_query_grant 流程定义种子）。冒烟启动 "Successfully validated 10 migrations" + "Migrating to version 10 - m2 query grant flow" + "Successfully applied 1 migration, now at version v10"。flyway_schema_history 至 V10。

## 4. 构建、测试与集成冒烟证据

### 单元测试（BUILD SUCCESS / Failures:0）

`mvn -pl ruoyi-modules/ruoyi-db-workflow -am test -DskipTests=false -Dtest=GrantApplicationServiceImplTest,GrantApprovalCallbackServiceImplTest` → **Tests run: 7, Failures: 0, Errors: 0, Skipped: 0**（apply 自批拒绝 3 + 回调生成/拒绝/幂等/未知单 4）。全 reactor `mvn package -DskipTests` BUILD SUCCESS，ruoyi-admin.jar 165MB 生成。

### 集成冒烟（应用对 VM，端口 8087，dev profile）

启动：`java -jar ruoyi-admin.jar --spring.profiles.active=dev --server.port=8087`。Flyway V10 应用 success，Warm-Flow v1.8.5 加载，全 db bean 装配通过，`Started DromaraApplication in 21.7s`。

**冒烟环境准备**（VM 192.168.149.128 PostgreSQL datagate）：
- M1 建的 smoke-mysql 数据源（id=2092902777709199361，192.168.149.128:3306 MYSQL，ACTIVE）+ QUERY 凭据（id=2092902826879025154，root，ACTIVE）复用。
- POST /db/datasource/{id}/sync 同步 smoke-mysql 元数据 → foundCount=13734 资源入库（DATABASE: data-gate/hr/mysql/... + TABLE: sys_menu/flow_node/...）。
- SQL 建 DBA 角色（role_id=10, role_key=dba）+ db:* 权限菜单（menu_id 20000-20007：db:workflow:apply/approve/list + db:console:query/cancel + db:datasource:sync/list）+ sys_role_menu 关联。
- 建用户 dbg_applicant（userId=2092911326631923713，配 dba 角色）。
- 首登改密拦截：dbg_applicant login → 41005 IAM_PASSWORD_CHANGE_REQUIRED（dbg_user_security.must_change_pwd）；SQL INSERT dbg_user_security(applicant, must_change_pwd=false) 绕过。

**端到端流程**（hr DATABASE QUERY 申请）：
1. dbg_applicant login → POST /db/workflow/apply（approverId=1 admin, subjectId=applicant, resourceId=hr DATABASE, action=QUERY, effect=ALLOW, expiresAt+1d）→ applicationId=2092911739288522754。
2. admin login → POST /db/workflow/approve → code=200。

**流程事件日志证据**：
- apply：FlowProcessEventHandler【流程任务事件发布】businessId=2092911739288522754 节点 apply（申请人节点）→【流程事件发布】节点 approve submit=true params={PASS:approve=1, initiator=applicant, businessId=...}（申请人提交后流转到审批节点，PASS:approve=1 锁定 admin 为审批办理人）→【流程任务事件发布】节点 approve 任务ID=2092911740509065217（审批节点 task 生成）。
- approve：WorkflowGlobalListener【流程已结束，状态更新为: finish】→ FlowProcessEventHandler【流程事件发布】flow_status=finish 节点 end params={handler=1, message=...} → **GrantApprovalEventListener【查询权限审批通过，触发授权生成：applicationId=2092911739288522754, approverId=1】**。

**验证结果（VM psql）**：

| 表 | 字段 | 值 |
|---|---|---|
| dbg_grant_application | status | APPROVED ✅ |
| dbg_grant_application | grant_id | 2092911741930934274 ✅（回填） |
| dbg_grant_application | approver_id | 1 (admin) ✅ |
| dbg_resource_grant | subject_id | applicant ✅ |
| dbg_resource_grant | resource_id | hr DATABASE ✅ |
| dbg_resource_grant | action/effect | QUERY/ALLOW ✅ |
| dbg_resource_grant | source_type | REQUEST ✅ |
| dbg_resource_grant | source_id | 申请单 id ✅（幂等键） |
| dbg_resource_grant | revoked_at | null ✅（活授权） |
| flow_instance | flow_status | finish ✅ |
| flow_instance | node_type | 2（结束节点）✅ |

**自批拒绝验证**：admin apply approverId=1(admin) → code=500 "申请人不能审批本人申请"（docs/03 §13 #9 端到端生效）。

**data-gate DATABASE QUERY 申请闭环**：同样链路（applicationId=2092911991873703937）apply→approve→Grant 生成（日志/数据一致）。

## 5. 安全/性能/恢复证据

- **失败关闭**：解析器缺省/资源不可解析 → DEFAULT_DENY；审批人校验失败（自批/非指定）→ 拒绝；approve 前查 task 不存在 → 拒绝。
- **申请人不能自批**：apply（approverId==applicantId）+ approve（approverId==applicantId）双校验，端到端验证。
- **审批人锁定**：apply 时 variables.put("PASS:approve", approverIdStr)，WarmFlow assignment listener 把审批节点 permissionList 锁定为该 approverId；审批节点 permissionFlag 留空（未指定审批人时无人可办——失败关闭）。
- **幂等**：GrantApprovalCallbackServiceImpl.onApproval 非 PENDING 不重复生成 Grant（source_id=申请单幂等键 + grant 表 uk 幂等约束）。
- **凭据安全**：申请人不接触数据库密码（凭据集中托管，授权命中后由执行器 resolve 解密短时驻留）；申请单 reason/conditions 不含秘密。
- **审计**：网关经 IAuditService 写查询执行/拒绝/前置拒审计（M2 查询链路已实现）；审批流自身经 WarmFlow flow_his_task 留痕。
- **审批人职责分离**：admin（超管）作审批人 bypass 权限边界（冒烟）；生产应配 DBA/资源 Owner 角色（已建 dba 角色范例）。

## 6. 与规格的偏差和 ADR

- **流程定义种子化方式**：V10 直接 SQL INSERT flow_definition/node/skip（非运行时 importDef JSON）。理由：符合项目 Flyway 既定迁移模式，幂等（ON CONFLICT DO NOTHING），不增加 db-workflow warm-flow 编译依赖，版本管理清晰。固定主键 9001-9008 避免与运行时雪花 ID 冲突。
- **reject/cancel 不走 WarmFlow 流程操作**：用 WorkflowService.deleteInstance（公共接口）+ updateStatus + 主动 onRejection。理由：WorkflowService.completeTask 固定 hisStatus=PASS（无法经公共接口拒绝）；deleteInstance 不发布 ProcessEvent，故主动调 onRejection 保证不生成 Grant。**偏差**：reject 暂用删除流程实例（丢失审批拒绝轨迹），完整 termination（warm-flow insService.terminate）待 warm-flow 深入接入。
- **approve 查 taskId 用自建 FlowTaskQueryMapper**：db-workflow 不依赖 ruoyi-workflow 模块，用原生 @Select 查 flow_task 待办 task。
- **approverId 字段语义**：V9 表 approver_id 注释"审批后回填"，实际 apply 时即设为目标审批人（用于 permissionList 锁定 + approve 校验 + 回调）。语义调整为"目标审批人=实际审批人"。
- **DBA 角色权限菜单为冒烟种子**：sys_menu 20000-20007 + sys_role 10 (dba) 仅冒烟用，生产应经权限管理界面配置；未写入 Flyway（不污染 prod，dev 手动建）。
- **dbg_user_security 首登改密绕过**：冒烟用 SQL 标记 applicant must_change_pwd=false（dev），生产首登改密流（IAM-002）正常生效。

## 7. 未完成项与风险

1. **带表查询闭环 AUTH_RESOURCE_DENIED（发现根因）**：applicant 有 data-gate DATABASE QUERY Grant（继承到表，docs/03 §5.1），但带表查询 sys_menu → REJECTED AUTH_RESOURCE_DENIED。**根因**：`AuthorizationDecisionServiceImpl.resolveAncestors` 依赖 `ResourceHierarchyResolver`（db-auth 端口），**该端口无正式实现类**（仅测试桩 StubHierarchyResolver），Optional 注入为空 → 只查资源自身不查祖先 → DATABASE 级 Grant 不命中 TABLE 级查询。注释已标注"解析器缺省时只查资源自身（不实现继承）"。**这是 M2 查询链路的剩余 bug，非 M2-02 流程装配问题**。
2. **reject/termination 完整轨迹**：见偏差，reject 暂用 deleteInstance。
3. **前端 plus-ui 控制台**：申请/审批/查询工作台（Monaco + 结果表 + 取消）。
4. **查询审计写入 / cancel 真实验证**：审计写入未端到端核验；cancel 需长查询验证。
5. **池 key 偏差**（继承 M2 检查点）：ConnectionContext 不含 credentialVersionId。

## 8. 下一步最小安全切片

**实现 `ResourceHierarchyResolver`（db-resource）**：按 dbg_resource.parent_id 递归查资源祖先链到 DATABASE/DATA_SOURCE 级，注入 db-auth 授权引擎。完成后带表查询闭环：applicant 有 DATABASE QUERY Grant → 查该库下表 → 授权引擎查表 + 祖先 DATABASE Grant（继承命中）→ ALLOW → 真实 MySQL 执行返回行。这是 M2 查询链路（M2-01 授权引擎 + M2-04 ResourcePathResolver）的真正端到端闭环（docs/10 M2 验收"带表查询"）。

— M2-02 WarmFlow 流程装配（申请→审批→授权生成）真实可跑、回调自动生成 Grant、申请人不能自批端到端验证通过。完整带表查询闭环待 ResourceHierarchyResolver 实现。
