package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;

/**
 * 查询执行器（docs/02 第 8 节、docs/06 第 4 节）。
 *
 * <p>执行器必须假设所有输入均不可信：即使调用方已做权限校验，
 * 也要验证执行计划有效期、数据源状态和最终限制；
 * 只接受 {@link ExecutionPlan}，拒绝“数据源 ID + 任意 SQL”形式的调用。</p>
 *
 * <p>并行冻结（ADR-007）：{@link #execute} 与 {@link #cancel} 签名在 M2/M3 并行期间稳定，
 * 变更须经 ADR 修订。</p>
 *
 * @author DataGate
 */
public interface QueryExecutor {

    /**
     * 受控流式执行（docs/02 §8.1 step 11-13、docs/06 §4 step 11-12、§11）。
     *
     * <p>执行器职责：</p>
     * <ul>
     *   <li>验证计划未过期（{@link ExecutionPlan#isExpired}）与数据源状态；</li>
     *   <li>设置只读事务/超时/会话保护，注册 executionNo 供跨节点取消；</li>
     *   <li>游标/流式读取，应用行/字节/单元格上限与字段脱敏（服务端流式阶段完成）；</li>
     *   <li>不持久保存结果正文；正常结束统一回滚/RESET 并清理会话状态。</li>
     * </ul>
     *
     * @param plan     不可变、服务端构造、已授权的执行计划
     * @param callback 行回调；列头先于行，值已脱敏；返回 false 终止读取
     * @return 执行元数据（含 executionNo、状态、耗时、行/字节、截断、错误码）
     */
    ExecutionResultMeta execute(ExecutionPlan plan, RowCallback callback);

    /**
     * 取消正在运行的执行。取消幂等；已结束的执行返回最终状态。
     *
     * @param executionNo 客户端可见执行号（由 {@link #execute} 生成并写入返回的 {@link ExecutionResultMeta}）
     */
    void cancel(String executionNo);
}
