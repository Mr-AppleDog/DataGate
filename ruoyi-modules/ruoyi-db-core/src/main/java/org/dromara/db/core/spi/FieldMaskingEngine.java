package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.MaskingLevel;

import java.util.List;

/**
 * 字段级脱敏引擎（docs/06 §11、docs/10 M5-05、docs/02 §7.1 FIELD_MASKING 能力）。
 *
 * <p>查询预览、普通结果与导出使用同一服务端脱敏引擎，前端永远接触不到原值（docs/10 M5-05）。
 * 引擎是纯算法：输入 (单元格值, 列静态策略, 运行时授权级别)，输出已脱敏单元格。
 * 失败关闭：任何异常不得泄露原值，降级为全掩码。</p>
 *
 * <p>级别语义：</p>
 * <ul>
 *   <li>UNMASKED：持有 COLUMN_UNMASK 授权，原值透传（由调用方保证授权）；</li>
 *   <li>MASKED：敏感列按 maskingType 掩码；非敏感/无策略列透传；</li>
 *   <li>HIDDEN：整列不返回值，仅占位（value=null）。</li>
 * </ul>
 *
 * <p>无法可靠判断来源的表达式列由调用方在生产环境按最高等级处理（传入 HIDDEN 或 RESTRICTED 策略）。</p>
 *
 * @author DataGate
 */
public interface FieldMaskingEngine {

    /**
     * 掩码单个单元格。
     *
     * @param cell   原始单元格（value 可空）
     * @param policy 列静态策略（可空：表示无已知策略，按 level 决定）
     * @param level  运行时授权级别
     * @return 已脱敏单元格（永不抛异常；原值不可泄露）
     */
    RowCell mask(RowCell cell, ColumnMaskingPolicy policy, MaskingLevel level);

    /**
     * 掩码一行单元格（列顺序与策略/级别列表对齐）。
     *
     * <p>策略或级别列表短于单元格数时，缺失位按无策略 + 该行默认级别处理（透传），
     * 不抛异常或越界。返回新列表，不修改入参。</p>
     *
     * @param cells   一行原始单元格
     * @param policies 每列静态策略（可空元素/可短）
     * @param levels   每列运行时级别（可短）
     * @return 已脱敏单元格列表
     */
    List<RowCell> maskRow(List<RowCell> cells, List<ColumnMaskingPolicy> policies, List<MaskingLevel> levels);
}
