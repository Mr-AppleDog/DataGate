package org.dromara.db.console.service.impl;

import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.spi.RowCallback;
import org.dromara.db.executor.domain.QueryExecutionRequest;
import org.dromara.db.executor.service.QueryExecutionGateway;

import java.util.List;

/**
 * 执行网关桩：发射列头+若干行，返回预设元数据，记录调用。
 */
public class StubQueryExecutionGateway implements QueryExecutionGateway {

    public ExecutionResultMeta cannedResult;
    public int emitRows = 2;
    public boolean invoked;
    public String canceledExecutionNo;

    @Override
    public ExecutionResultMeta execute(QueryExecutionRequest request, RowCallback clientCallback) {
        invoked = true;
        clientCallback.onHeader(new RowHeader(List.of(new ColumnMeta("c", "VARCHAR", "text"))));
        for (int i = 0; i < emitRows; i++) {
            clientCallback.onRow(List.of(new RowCell("v" + i, false, null)));
        }
        clientCallback.onComplete();
        return cannedResult;
    }

    @Override
    public void cancel(String executionNo) {
        canceledExecutionNo = executionNo;
    }
}
