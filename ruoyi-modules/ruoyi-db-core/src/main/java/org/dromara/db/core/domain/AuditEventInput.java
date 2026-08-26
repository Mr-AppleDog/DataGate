package org.dromara.db.core.domain;

import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;

import java.util.Map;

/**
 * 审计事件输入（docs/08 第 9.2 节）。
 *
 * <p>约束：details/actorSnapshot/targetSnapshot 禁止包含查询结果、
 * 密码明文、密文、完整 JDBC URL 与未脱敏 SQL 字面量（docs/08 第 11 节）。</p>
 *
 * @param category       审计类别
 * @param action         规范动作（如 DATASOURCE_CREATE、CREDENTIAL_ROTATE）
 * @param actorId        操作人（系统任务为 null）
 * @param actorSnapshot  操作人快照（用户名/部门等）
 * @param targetType     目标类型（DATA_SOURCE/CREDENTIAL/GRANT...）
 * @param targetId       目标 ID
 * @param targetSnapshot 遮蔽后的目标快照
 * @param result         结果
 * @param sourceIp       来源 IP
 * @param userAgent      客户端
 * @param traceId        链路追踪
 * @param details        扩展明细（不含秘密与结果正文）
 * @author DataGate
 */
public record AuditEventInput(
    AuditCategory category,
    String action,
    Long actorId,
    Map<String, Object> actorSnapshot,
    String targetType,
    String targetId,
    Map<String, Object> targetSnapshot,
    AuditResult result,
    String sourceIp,
    String userAgent,
    String traceId,
    Map<String, Object> details
) {

    public AuditEventInput {
        if (category == null || action == null || action.isBlank() || result == null) {
            throw new IllegalArgumentException("audit category/action/result must not be blank");
        }
    }
}
