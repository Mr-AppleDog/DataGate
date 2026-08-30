# [M2][QRY] KEK 不可用被返回为通用 HTTP 200 / 业务码 500

状态：`SUBMITTED`  
GitHub：https://github.com/Mr-AppleDog/DataGate/issues/3

## 问题概述

查询凭据所需 KEK 版本不可用时，查询工作台只收到 HTTP `200`、业务码 `500` 和“发生未知异常”，没有返回代码中已经定义的稳定错误 `CREDENTIAL_KEK_UNAVAILABLE`。

## 需求与影响

- 需求 / 验收来源：`docs/05-API错误码与状态机规范.md` 的稳定 HTTP/业务错误边界；`docs/06-数据源适配与安全执行规范.md:316`；`DbErrorCode.CREDENTIAL_KEK_UNAVAILABLE`（`44004` / HTTP `503`）。
- 严重程度：`S2`
- 用户 / 业务影响：正常只读查询无法区分可重试的密钥服务不可用与未知系统故障，前端、告警和运维无法采取正确恢复动作。
- 关联测试用例 / 缺陷：`ST-QRY-002` / `DEF-QRY-001`

## 环境

- 构建版本 / Git 提交：`8e94aea694d68438d810359c656c36d1b5354487`，包含未提交的本地变更
- 环境类型：`local`
- 浏览器：Microsoft Edge + Codex 扩展
- 用户角色：现有已登录本地测试/管理员会话
- 测试数据状态：现有 ACTIVE 测试数据源；其 QUERY 凭据引用的 KEK 版本在当前运行环境不可用

## 最小复现步骤

1. 以有查询权限的用户进入“数据工作台 → 查询控制台”。
2. 选择现有 ACTIVE 测试数据源。
3. 执行只读常量语句 `SELECT 1`。

## 预期结果

返回稳定的 `CREDENTIAL_KEK_UNAVAILABLE`（业务码 `44004`，语义 HTTP `503`，可重试），且不泄露密钥或凭据细节。

## 实际结果

请求返回 HTTP `200`、业务码 `500`、消息“发生未知异常，请联系管理员”，无稳定错误码；服务端诊断边界为 `IllegalStateException: KEK 版本不可用`。

## 复现概率

- `3/3`

## 已脱敏证据

- API：`test-artifacts/system-test/runs/2026-08-30/ST-QRY-001/evidence/edge/select-one-observation.json`
- Edge 截图 / 可见状态：`test-artifacts/system-test/runs/2026-08-30/ST-QRY-001/evidence/edge/select-one-failure.png`
- 日志 / 错误码：保留脱敏异常类与消息；未保留原始日志、连接信息或密钥材料

## 故障边界与源码候选

- 证据支持的最小故障边界：凭据解密的 KEK 缺失异常到 Web 稳定错误响应之间的转换链路。
- 源码候选或待验证假设：`CredentialCryptoService.java:110-112` 抛出未分类 `IllegalStateException`；`QueryExecutionGatewayImpl.java:195-200` 在执行器异常边界之前解析凭据；最终由 `GlobalExceptionHandler.java:149-153` 转成通用失败。代码中 `DbErrorCode.java:41` 已存在目标稳定错误。

## 临时解决方案或恢复方式

为运行环境配置与现有凭据匹配的 KEK 版本可恢复查询，但不能修复错误映射缺陷；不得通过重新录入或导出凭据规避。

## 安全检查

- [x] 不包含密码、Token、Cookie、密钥、签名 URL 或完整连接串
- [x] 不包含个人数据、查询结果或无关日志
- [x] 内部主机、IP、库名和租户信息已删除或确有必要且允许披露
- [x] 已搜索 open/closed issues，未发现同根因重复项
