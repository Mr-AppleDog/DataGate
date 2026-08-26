package org.dromara.db.core.spi;

import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;

import java.util.List;

/**
 * 行流式回调（docs/06 第 11 节）。
 *
 * <p>执行器在首批行前恰好调用一次 {@link #onHeader}，随后逐行调用 {@link #onRow}；
 * 行的列顺序与 header 一致，值已服务端流式脱敏；二进制列只给类型/长度/摘要。
 * 调用方返回 false 提前终止读取（如客户端已达上限或主动取消）。</p>
 *
 * <p>并行冻结（ADR-007）：本接口在 M2/M3 并行期间稳定，变更须经 ADR 修订。</p>
 *
 * @author DataGate
 */
public interface RowCallback {

    /**
     * 列元数据。执行器保证恰好在任意 {@link #onRow} 之前调用一次。
     */
    void onHeader(RowHeader header);

    /**
     * 吐出一行已脱敏数据。
     *
     * @param cells 单元格列表，顺序与 {@link #onHeader} 一致
     * @return false 提前终止读取；true 继续
     */
    boolean onRow(List<RowCell> cells);

    /**
     * 末尾信号，回调可 flush。默认空实现。
     */
    default void onComplete() {
    }

    /**
     * 执行器捕获到流式过程中的非致命异常时回调。
     *
     * @param t 异常
     * @return true 已处理（执行器以错误结束）；默认 false 由执行器向上抛出
     */
    default boolean onError(Throwable t) {
        return false;
    }
}
