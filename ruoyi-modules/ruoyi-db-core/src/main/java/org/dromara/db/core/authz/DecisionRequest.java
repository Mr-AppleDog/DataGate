package org.dromara.db.core.authz;

import org.dromara.db.core.enums.DbAction;

import java.util.Map;

/**
 * 授权判定请求（docs/03 第 7.1 节）。
 *
 * <p>资源 ID 与动作由解析器与资源目录服务解析得到，不接受客户端直接构造；
 * 禁止包含数据库密码或完整 SQL（docs/03 第 8 节缓存约束）。</p>
 *
 * @param actorId        操作人（系统任务为 null）
 * @param sessionId      会话
 * @param sourceIp       来源 IP
 * @param resourceId     目标资源 ID
 * @param action         资源动作
 * @param requestContext 请求上下文（查询类型、请求行数、执行时间、是否导出等，非秘密）
 * @author DataGate
 */
public record DecisionRequest(
    Long actorId,
    String sessionId,
    String sourceIp,
    Long resourceId,
    DbAction action,
    Map<String, Object> requestContext
) {

    public DecisionRequest {
        requestContext = requestContext == null ? Map.of() : Map.copyOf(requestContext);
    }
}
