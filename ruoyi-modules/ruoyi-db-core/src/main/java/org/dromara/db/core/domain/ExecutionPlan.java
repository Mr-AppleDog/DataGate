package org.dromara.db.core.domain;

import java.time.Instant;
import java.util.List;

/**
 * 服务端内部执行计划（docs/02 第 8.2 节）。
 * 不可由客户端构造；执行器拒绝直接接收“数据源 ID + 任意 SQL”形式的调用。
 *
 * @param planId               计划 ID
 * @param userId               用户
 * @param dataSourceId         数据源
 * @param databaseName         目标库
 * @param schemaName           目标 Schema（可空）
 * @param statementHash        原始语句 SHA-256
 * @param normalizedStatement  归一化语句
 * @param statementType        语句类型
 * @param resourceIds          引用资源 ID
 * @param decisionId           授权决策 ID
 * @param maxRows              最大行数（授权与环境硬限制合并结果）
 * @param maxBytes             最大字节数
 * @param maxExecutionSeconds  最长执行秒数
 * @param createdAt            创建时间（UTC）
 * @param expiresAt            短期过期时间（UTC）
 * @author DataGate
 */
public record ExecutionPlan(
    String planId,
    Long userId,
    Long dataSourceId,
    String databaseName,
    String schemaName,
    String statementHash,
    String normalizedStatement,
    String statementType,
    List<Long> resourceIds,
    String decisionId,
    long maxRows,
    long maxBytes,
    long maxExecutionSeconds,
    Instant createdAt,
    Instant expiresAt
) {

    public ExecutionPlan {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
    }

    /**
     * 计划是否已过期（过期计划执行器必须拒绝）
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
