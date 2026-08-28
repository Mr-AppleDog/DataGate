package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ColumnMaskingPolicy;

import java.util.Collection;
import java.util.Map;

/**
 * 列脱敏策略解析器（docs/04 §3.7、docs/10 M5-05）。
 *
 * <p>按 COLUMN 资源 ID 批量加载静态脱敏策略（敏感等级 + 脱敏类型），
 * 供查询/导出执行器在服务端流式阶段应用。查询预览、普通结果与导出使用同一引擎（docs/10 M5-05）。</p>
 *
 * <p>解析器只读静态策略；运行时授权级别（MASKED/UNMASKED/HIDDEN）由鉴权决定，不由本解析器提供。</p>
 *
 * @author DataGate
 */
public interface ColumnMaskingPolicyResolver {

    /**
     * 批量解析列脱敏策略（按 COLUMN 资源 ID）。
     *
     * @param columnResourceIds COLUMN 资源 ID 集合（可空）
     * @return resourceId -> ColumnMaskingPolicy；无策略的列不出现在结果中
     */
    Map<Long, ColumnMaskingPolicy> resolve(Collection<Long> columnResourceIds);

    /**
     * 按表资源解析列脱敏策略（docs/06 §11 列来源追踪，M5-05c）。
     *
     * <p>返回引用表下所有 COLUMN 子资源的策略，键 = (表物理名.列名).toLowerCase，
     * 未标注列给默认 PUBLIC/NONE 策略（非敏感透传，不误隐藏直接引用列）。
     * 执行器按 JDBC getTableName/getColumnName 基列名（非别名）查键，防 SELECT sensitive AS x 绕过。</p>
     *
     * @param tableResourceIds TABLE/VIEW 资源 ID 集合（可空）
     * @return (表.列)lowercase -> ColumnMaskingPolicy（含未标注列默认策略）
     */
    default Map<String, ColumnMaskingPolicy> resolveByTableColumn(Collection<Long> tableResourceIds) {
        return java.util.Map.of();
    }
}
