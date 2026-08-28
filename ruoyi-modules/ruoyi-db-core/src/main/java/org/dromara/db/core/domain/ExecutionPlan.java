package org.dromara.db.core.domain;

import org.dromara.db.core.enums.MaskingLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 服务端内部执行计划（docs/02 第 8.2 节）。
 * 不可由客户端构造；执行器拒绝直接接收"数据源 ID + 任意 SQL"形式的调用。
 *
 * <p>M5-05c 扩展：承载字段级脱敏上下文（maskingLevel + 列策略 + 列明文级别），
 * 供执行器在服务端流式阶段应用脱敏。ExecutionPlan 非 ADR-007 冻结对象，可扩展。</p>
 *
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
    Instant expiresAt,
    /** 运行时脱敏级别（资源级，鉴权合并结果；prod 默认 MASKED，无资源引用查询 UNMASKED） */
    MaskingLevel maskingLevel,
    /** 列静态策略：键 = (表物理名.列名).toLowerCase，值 = ColumnMaskingPolicy（含未标注列的默认 PUBLIC/NONE） */
    Map<String, ColumnMaskingPolicy> columnPolicies,
    /** 列明文级别覆盖：键同上，仅出现持有 COLUMN_UNMASK 临时授权且通过二次认证的列，值 = UNMASKED */
    Map<String, MaskingLevel> columnUnmaskLevels
) {

    public ExecutionPlan {
        resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        maskingLevel = maskingLevel == null ? MaskingLevel.UNMASKED : maskingLevel;
        columnPolicies = columnPolicies == null ? Map.of() : Map.copyOf(columnPolicies);
        columnUnmaskLevels = columnUnmaskLevels == null ? Map.of() : Map.copyOf(columnUnmaskLevels);
    }

    /**
     * 计划是否已过期（过期计划执行器必须拒绝）
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /**
     * 不带脱敏上下文的工厂（默认 UNMASKED + 空策略），供测试与非脱敏场景构造。
     * 生产网关须用全参构造器显式传入 maskingLevel/columnPolicies/columnUnmaskLevels。
     */
    public static ExecutionPlan of(
        String planId, Long userId, Long dataSourceId, String databaseName, String schemaName,
        String statementHash, String normalizedStatement, String statementType,
        List<Long> resourceIds, String decisionId,
        long maxRows, long maxBytes, long maxExecutionSeconds,
        Instant createdAt, Instant expiresAt
    ) {
        return new ExecutionPlan(
            planId, userId, dataSourceId, databaseName, schemaName,
            statementHash, normalizedStatement, statementType, resourceIds, decisionId,
            maxRows, maxBytes, maxExecutionSeconds, createdAt, expiresAt,
            MaskingLevel.UNMASKED, Map.of(), Map.of());
    }
}
