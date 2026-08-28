package org.dromara.db.core.domain;

import org.dromara.db.core.enums.MaskingLevel;

import java.util.List;
import java.util.Map;

/**
 * 导出执行请求（docs/06 §12、docs/02 §6.6 导出执行策略）。
 *
 * <p>由导出工单服务在执行前重新解析+重新鉴权后组装（不可由客户端直接构造）：
 * statement 为已解密锁定的 SQL；maskingLevel/columnPolicies/columnUnmaskLevels 为执行时脱敏上下文。
 * 执行器据此流式读取结果、服务端脱敏、CSV 公式注入防护后写入加密对象。</p>
 *
 * @author DataGate
 */
public record ExportExecutionRequest(
    Long jobId,
    Long userId,
    String sessionId,
    String sourceIp,
    Long dataSourceId,
    String databaseName,
    String schemaName,
    /** 已解密锁定的 SQL（执行前重新鉴权） */
    String statement,
    List<Long> resourceIds,
    String decisionId,
    MaskingLevel maskingLevel,
    Map<String, ColumnMaskingPolicy> columnPolicies,
    Map<String, MaskingLevel> columnUnmaskLevels,
    long maxRows,
    long maxBytes,
    long maxExecutionSeconds
) {

    public ExportExecutionRequest {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        columnPolicies = columnPolicies == null ? Map.of() : Map.copyOf(columnPolicies);
        columnUnmaskLevels = columnUnmaskLevels == null ? Map.of() : Map.copyOf(columnUnmaskLevels);
    }
}
