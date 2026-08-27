package org.dromara.db.executor.domain;

/**
 * 查询执行请求（docs/02 第 8.1 节编排入口）。
 *
 * <p>由 db-console 装配：userId/sessionId/sourceIp 取自 Sa-Token 会话，
 * dataSourceId/databaseName/schemaName 取自控制台选择，statement 为用户提交 SQL，
 * clientMaxRows/clientMaxBytes 为客户端申请上限（null 用环境默认）。</p>
 *
 * @param userId        操作人（系统任务为 null）
 * @param sessionId     会话
 * @param sourceIp      来源 IP（鉴权条件 sourceIpCidr + 审计）
 * @param dataSourceId  数据源
 * @param databaseName  目标库（null 用数据源默认库）
 * @param schemaName    目标 Schema（可空，PG）
 * @param statement     用户提交语句
 * @param clientMaxRows 客户端申请行数上限（null 用默认）
 * @param clientMaxBytes 客户端申请字节上限（null 用默认）
 * @author DataGate
 */
public record QueryExecutionRequest(
    Long userId,
    String sessionId,
    String sourceIp,
    Long dataSourceId,
    String databaseName,
    String schemaName,
    String statement,
    Long clientMaxRows,
    Long clientMaxBytes
) {
    public QueryExecutionRequest {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("dataSourceId required");
        }
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("statement required");
        }
        if (statement.length() > 1_000_000) {
            throw new IllegalArgumentException("statement too long");
        }
    }
}
