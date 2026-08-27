package org.dromara.db.console.service.impl;

import org.dromara.db.console.domain.QueryResultView;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.executor.domain.QueryExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 查询控制台编排服务单元测试。
 */
@Tag("unit")
class DbConsoleServiceImplTest {

    private StubQueryExecutionGateway gateway;
    private DbConsoleServiceImpl service;

    @BeforeEach
    void setUp() {
        gateway = new StubQueryExecutionGateway();
        gateway.cannedResult = new ExecutionResultMeta("exec-1", ExecutionStatus.SUCCEEDED,
            5, 2, 200, false, null);
        service = new DbConsoleServiceImpl(gateway);
    }

    private QueryExecutionRequest req(Long clientMaxRows) {
        return new QueryExecutionRequest(1L, "tok", "10.0.0.1", 1L, null, null,
            "select * from orders", clientMaxRows, null);
    }

    @Test
    void executeReturnsCollectedRowsAndMeta() {
        QueryResultView view = service.execute(req(null));
        assertEquals("exec-1", view.executionNo());
        assertEquals("SUCCEEDED", view.status());
        assertEquals(1, view.columns().size());
        assertEquals(2, view.rows().size());
        assertEquals("v0", view.rows().get(0).get(0).value());
        assertTrue(gateway.invoked);
    }

    @Test
    void clientMaxRowsTruncatesCollector() {
        gateway.emitRows = 3;
        QueryResultView view = service.execute(req(2L));
        assertEquals(2, view.rows().size(), "客户端上限应截断收集");
    }

    @Test
    void defaultMaxRowsAppliedWhenClientNull() {
        gateway.emitRows = 600;
        QueryResultView view = service.execute(req(null));
        assertEquals(500, view.rows().size(), "默认客户端上限 500");
    }

    @Test
    void cancelDelegatesToGateway() {
        service.cancel("exec-9");
        assertEquals("exec-9", gateway.canceledExecutionNo);
    }
}
