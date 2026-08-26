package org.dromara.db.core.domain;

/**
 * 单个单元格已脱敏值（docs/06 第 11 节）。
 *
 * <p>约束：服务端流式阶段完成脱敏，前端永远接触不到原值；
 * 单元格硬上限 1 MB，超出截断并标记；二进制默认不展示原值。</p>
 *
 * @param value         已脱敏文本值；二进制列或 NULL 为 null（见 binarySummary）
 * @param truncated     是否因单元格/列上限被截断
 * @param binarySummary 二进制列的摘要（类型/长度/哈希摘要）；非二进制列为 null
 * @author DataGate
 */
public record RowCell(String value, boolean truncated, String binarySummary) {
}
