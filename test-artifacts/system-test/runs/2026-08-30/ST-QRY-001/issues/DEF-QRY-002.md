# [M2][QRY] 查询取消的客户端 POST、后端 GET 与规范 DELETE 不一致

状态：`SUBMITTED`  
GitHub：https://github.com/Mr-AppleDog/DataGate/issues/4

## 问题概述

查询工作台的“取消”按钮发送 POST，但后端只接受 GET，规范要求 DELETE，导致取消请求被拒绝且页面没有稳定反馈。

## 需求与影响

- 需求 / 验收来源：`docs/05-API错误码与状态机规范.md:120,139-145`；`docs/06-数据源适配与安全执行规范.md:22,127-129,323`；M2 查询控制台验收。
- 严重程度：`S1`
- 用户 / 业务影响：用户无法通过工作台取消运行中查询；长查询可能继续占用目标库连接和资源，且终态查询的幂等取消契约也无法满足。
- 关联测试用例 / 缺陷：`ST-QRY-004` / `DEF-QRY-002`

## 环境

- 构建版本 / Git 提交：`8e94aea694d68438d810359c656c36d1b5354487`，包含未提交的本地变更
- 环境类型：`local`
- 浏览器：Microsoft Edge + Codex 扩展
- 用户角色：现有已登录本地测试/管理员会话
- 测试数据状态：无效非写入语句产生一个终态 `REJECTED` 执行编号；未创建业务数据

## 最小复现步骤

1. 进入“数据工作台 → 查询控制台”。
2. 提交一条无效、非写入语句，使页面显示终态执行编号。
3. 点击“取消”。

## 预期结果

客户端按规范发送 DELETE；服务端幂等接受取消，或对已结束查询返回当前最终状态，并显示可操作的稳定结果。

## 实际结果

前端发送 POST；服务端诊断为 `Request method POST is not supported`；页面没有成功反馈或稳定错误码。

## 复现概率

- `4/4`

## 已脱敏证据

- API：`test-artifacts/system-test/runs/2026-08-30/ST-QRY-001/evidence/edge/query-cancel-observation.json`
- Edge 截图 / 可见状态：`test-artifacts/system-test/runs/2026-08-30/ST-QRY-001/evidence/edge/query-cancel-no-feedback.png`
- 日志 / 错误码：仅保留 HTTP 方法不支持的脱敏摘要；未保留执行编号、会话值或原始日志

## 故障边界与源码候选

- 证据支持的最小故障边界：查询取消的前端 API 方法、后端 Controller 路由和文档契约不一致。
- 源码候选或待验证假设：`plus-ui/src/api/db/console.ts:58-62` 使用 POST；`DbConsoleController.java:61-64` 使用 GET；两者均未实现 `docs/05` 规定的 DELETE。

## 临时解决方案或恢复方式

无安全的 UI 临时方案；不建议用户直接调用未按规范暴露的 GET 路由。

## 安全检查

- [x] 不包含密码、Token、Cookie、密钥、签名 URL 或完整连接串
- [x] 不包含个人数据、查询结果或无关日志
- [x] 内部主机、IP、库名和租户信息已删除或确有必要且允许披露
- [x] 已搜索 open/closed issues，未发现同根因重复项
