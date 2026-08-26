package org.dromara.db.core.domain;

/**
 * 列元数据。
 *
 * @param name        列名/别名
 * @param typeName    引擎类型名（如 VARCHAR、BIGINT、INT8）
 * @param displayType 展示类型（可空，由连接器映射，如 text/number/binary/timestamp）
 * @author DataGate
 */
public record ColumnMeta(String name, String typeName, String displayType) {
}
