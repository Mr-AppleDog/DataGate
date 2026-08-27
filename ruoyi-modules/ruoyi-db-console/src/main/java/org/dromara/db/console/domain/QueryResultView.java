package org.dromara.db.console.domain;

import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.RowCell;

import java.util.List;

/**
 * 查询结果视图（M2-04）。结果只存于当前页面内存（docs/02 §11）。
 *
 * <p>不含 SQL 参数与原始语句；rows 已服务端流式脱敏/截断后回吐。</p>
 *
 * @param columns    列元数据
 * @param rows       已脱敏行（客户端上限内）
 * @param executionNo 客户端可见执行号（供取消）
 * @param status      执行状态
 * @param rowCount    返回行数
 * @param resultBytes 返回字节数
 * @param truncated   是否被平台限制截断
 * @param durationMs   耗时
 * @param errorCode   失败时的平台标准错误码名
 * @author DataGate
 */
public record QueryResultView(
    List<ColumnMeta> columns,
    List<List<RowCell>> rows,
    String executionNo,
    String status,
    long rowCount,
    long resultBytes,
    boolean truncated,
    long durationMs,
    String errorCode
) {
}
