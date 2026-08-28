package org.dromara.db.core.masking;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.spi.FieldMaskingEngine;

import java.util.List;

/**
 * 执行器流式脱敏应用器（docs/06 §11、docs/10 M5-05c）。
 *
 * <p>纯静态方法：按结果列来源（JDBC getTableName/getColumnName 基列名，非别名）
 * 查询执行计划中的列策略，结合运行时级别决定每列脱敏行为。失败关闭：未知来源在 MASKED 上下文 → HIDDEN，
 * 防止 SELECT sensitive AS x / 表达式借名绕过脱敏（docs/06 §11"无法可靠判断来源按最高等级"）。</p>
 *
 * <p>级别决策：</p>
 * <ul>
 *   <li>命中策略且敏感 + MASKED → 按 maskingType 掩码；</li>
 *   <li>命中策略但非敏感（PUBLIC/NONE） + MASKED → 透传（不误伤直接引用的未标注列）；</li>
 *   <li>命中策略且列明文级别 UNMASKED（COLUMN_UNMASK 临时授权） → 原值透传；</li>
 *   <li>未命中策略（表达式/别名/未知来源） + MASKED → HIDDEN（安全兜底）；</li>
 *   <li>base 级别 UNMASKED（无资源引用查询如 SELECT 1） → 透传。</li>
 * </ul>
 *
 * @author DataGate
 */
public final class MaskingApplier {

    private MaskingApplier() {
    }

    /**
     * 掩码单个单元格。
     *
     * @param cell   原始单元格
     * @param table  结果列来源表（JDBC getTableName，可空）
     * @param column 结果列基列名（JDBC getColumnName，可空）
     * @param plan   执行计划（含 maskingLevel/columnPolicies/columnUnmaskLevels）
     * @param engine 脱敏引擎
     * @return 已脱敏单元格
     */
    public static RowCell apply(RowCell cell, String table, String column, ExecutionPlan plan, FieldMaskingEngine engine) {
        if (plan == null || engine == null) {
            return cell;
        }
        MaskingLevel base = plan.maskingLevel();
        String key = key(table, column);
        ColumnMaskingPolicy policy = plan.columnPolicies().get(key);
        MaskingLevel level = plan.columnUnmaskLevels().getOrDefault(key, base);
        if (policy == null && level == MaskingLevel.MASKED) {
            // 未知来源在 MASKED 上下文 → 隐藏（安全兜底，防借名绕过）
            level = MaskingLevel.HIDDEN;
        }
        return engine.mask(cell, policy, level);
    }

    /**
     * 掩码一行（列顺序与 tables/columns 对齐）。
     */
    public static List<RowCell> applyRow(List<RowCell> cells, List<String> tables, List<String> columns,
                                         ExecutionPlan plan, FieldMaskingEngine engine) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        int n = cells.size();
        RowCell[] out = new RowCell[n];
        for (int i = 0; i < n; i++) {
            String t = (tables != null && i < tables.size()) ? tables.get(i) : null;
            String c = (columns != null && i < columns.size()) ? columns.get(i) : null;
            out[i] = apply(cells.get(i), t, c, plan, engine);
        }
        return List.of(out);
    }

    /**
     * 列策略键：(表物理名.列名).toLowerCase。
     */
    static String key(String table, String column) {
        String t = table == null ? "" : table.toLowerCase();
        String c = column == null ? "" : column.toLowerCase();
        return t + "." + c;
    }
}
