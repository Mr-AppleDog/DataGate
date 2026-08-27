package org.dromara.db.console.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.db.console.domain.QueryResultView;
import org.dromara.db.console.service.DbConsoleService;
import org.dromara.db.console.support.ConsoleResultCollector;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.executor.domain.QueryExecutionRequest;
import org.dromara.db.executor.service.QueryExecutionGateway;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询控制台编排实现（M2-04）。
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class DbConsoleServiceImpl implements DbConsoleService {

    /** 客户端默认行数上限（docs/10 M2-04：交互默认 500 行） */
    static final long DEFAULT_CLIENT_MAX_ROWS = 500L;

    private final QueryExecutionGateway gateway;

    @Override
    public QueryResultView execute(QueryExecutionRequest request) {
        long clientMaxRows = request.clientMaxRows() != null ? request.clientMaxRows() : DEFAULT_CLIENT_MAX_ROWS;
        ConsoleResultCollector collector = new ConsoleResultCollector(clientMaxRows);
        ExecutionResultMeta meta = gateway.execute(request, collector);
        RowHeader header = collector.header();
        return new QueryResultView(
            header == null ? List.of() : header.columns(),
            collector.rows(),
            meta.executionNo(),
            meta.status().name(),
            meta.rowCount(),
            meta.resultBytes(),
            meta.truncated(),
            meta.durationMs(),
            meta.errorCode());
    }

    @Override
    public void cancel(String executionNo) {
        gateway.cancel(executionNo);
    }
}
