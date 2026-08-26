package org.dromara.db.core.domain;

import java.util.List;

/**
 * 结果集列头（docs/06 第 11 节）。
 *
 * @param columns 列元数据，顺序与行数据一致
 * @author DataGate
 */
public record RowHeader(List<ColumnMeta> columns) {

    public RowHeader {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
