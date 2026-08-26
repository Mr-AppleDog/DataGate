package org.dromara.db.core.spi;

import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.SlowQueryRecord;

import java.util.List;

/**
 * 慢查询采集提供者（docs/07）。
 * 实现方负责增量游标、去重与重启/轮转/重置检测；记录必须先完成敏感字面量清理。
 *
 * @author DataGate
 */
public interface SlowQueryProvider {

    /**
     * 增量拉取慢查询记录
     *
     * @param cursor 上次游标
     * @param limit  单次拉取上限
     * @return 标准化记录与新游标
     */
    SlowQueryPage pull(CollectorCursor cursor, int limit);

    /**
     * 一页慢查询记录
     *
     * @param records   记录列表
     * @param nextCursor 下一游标
     */
    record SlowQueryPage(List<SlowQueryRecord> records, CollectorCursor nextCursor) {
    }
}
