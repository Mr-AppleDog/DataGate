package org.dromara.db.console.service;

import org.dromara.db.console.domain.QueryResultView;
import org.dromara.db.executor.domain.QueryExecutionRequest;

/**
 * 查询控制台编排服务（M2-04，docs/02 §6.5）。
 *
 * <p>接收经服务端注入身份的执行请求，调用执行网关流式回吐，返回有界结果视图。
 * 不持久保存查询结果（docs/02 §11）；历史只保存 SQL/指纹与执行元数据。</p>
 *
 * @author DataGate
 */
public interface DbConsoleService {

    /**
     * 同步执行查询（生产控制台默认单条、有界结果）。
     */
    QueryResultView execute(QueryExecutionRequest request);

    /**
     * 取消正在运行的执行（幂等）。
     */
    void cancel(String executionNo);
}
