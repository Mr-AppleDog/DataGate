package org.dromara.db.core.domain;

import java.util.List;

/**
 * 变更执行请求（docs/06 §13、§8.4，M5-02/M5-03）。
 *
 * <p>由变更工单服务在执行前重新解析+重新鉴权后组装。SQL 变更用 statement（解密锁定 SQL）；
 * Redis 变更用 redisCommands（结构化命令列表，经校验）+ authorizedPrefixes（工单授权 key 前缀）。
 * idempotencyKey 绑定用户+动作+摘要防重放（docs/08 §10）。</p>
 *
 * @param jobId            工单 ID
 * @param userId           操作人
 * @param sessionId        会话
 * @param sourceIp         来源 IP
 * @param dataSourceId     数据源
 * @param databaseName     目标库
 * @param schemaName       目标 Schema
 * @param statement        SQL 变更：已解密锁定 SQL（可含多语句）；Redis 变更可空
 * @param resourceIds      引用资源 ID
 * @param decisionId       鉴权决策 ID
 * @param idempotencyKey   幂等键
 * @param maxExecutionSeconds 执行超时
 * @param authorizedPrefixes Redis 变更：工单授权 key 前缀集合（逐 key 鉴权，docs/06 §8.4）
 * @param redisCommands    Redis 变更：结构化命令列表（经白名单/前缀校验）
 * @author DataGate
 */
public record ChangeExecutionRequest(
    Long jobId,
    Long userId,
    String sessionId,
    String sourceIp,
    Long dataSourceId,
    String databaseName,
    String schemaName,
    String statement,
    List<Long> resourceIds,
    String decisionId,
    String idempotencyKey,
    long maxExecutionSeconds,
    List<String> authorizedPrefixes,
    List<RedisChangeCommand> redisCommands
) {

    public ChangeExecutionRequest {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        authorizedPrefixes = authorizedPrefixes == null ? List.of() : List.copyOf(authorizedPrefixes);
        redisCommands = redisCommands == null ? List.of() : List.copyOf(redisCommands);
    }
}
