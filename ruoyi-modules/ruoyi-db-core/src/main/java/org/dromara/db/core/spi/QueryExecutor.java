package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ExecutionPlan;

/**
 * 查询执行器（docs/02 第 8 节）。
 *
 * <p>执行器必须假设所有输入均不可信：即使调用方已做权限校验，
 * 也要验证执行计划有效期、数据源状态和最终限制；
 * 只接受 {@link ExecutionPlan}，拒绝“数据源 ID + 任意 SQL”形式的调用。</p>
 *
 * @author DataGate
 */
public interface QueryExecutor {

    /**
     * 取消正在运行的执行。取消幂等；已结束的执行返回最终状态。
     *
     * @param executionNo 客户端可见执行号
     */
    void cancel(String executionNo);

    // M2 补充：execute(ExecutionPlan plan, RowCallback callback, ...) 流式执行签名，
    // 结果行经回调流出，执行器不持久保存结果。
}
