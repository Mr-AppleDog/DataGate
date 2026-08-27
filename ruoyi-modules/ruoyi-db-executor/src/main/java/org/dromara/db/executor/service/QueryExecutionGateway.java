package org.dromara.db.executor.service;

import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.spi.RowCallback;
import org.dromara.db.executor.domain.QueryExecutionRequest;

/**
 * 查询执行网关（docs/02 第 6.6 节、§8.1 编排顺序）。
 *
 * <p>编排：解析数据源/凭据 → 方言解析 → 逐资源授权判定 → 生成不可变 ExecutionPlan
 * → 组装 ConnectionContext → 调连接器执行器流式回吐 → 写查询审计。
 * 禁止直接接收“数据源 ID + 任意 SQL”的内部调用——statement 必须经解析校验。</p>
 *
 * <p>纵深防御：执行器（连接器侧）独立重新解析 originalStatement，非只读/多语句一律 REJECTED。
 * 本网关亦对结果施加行/字节/单元格硬上限（即便连接器实现有缺陷也不溢出）。</p>
 *
 * @author DataGate
 */
public interface QueryExecutionGateway {

    /**
     * 受控流式执行（生产控制台默认单条，docs/06 第 4 节）。
     *
     * @param request    执行请求
     * @param clientCallback 客户端行回调（结果经网关二次限流后回吐）
     * @return 执行元数据（含 executionNo、状态、耗时、行/字节、截断、错误码）
     */
    ExecutionResultMeta execute(QueryExecutionRequest request, RowCallback clientCallback);

    /**
     * 取消正在运行的执行（幂等；已结束返回最终状态）。
     *
     * @param executionNo 客户端可见执行号（由 execute 返回的 ExecutionResultMeta 携带）
     */
    void cancel(String executionNo);
}
