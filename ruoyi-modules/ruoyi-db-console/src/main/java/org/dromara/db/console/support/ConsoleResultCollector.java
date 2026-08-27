package org.dromara.db.console.support;

import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.spi.RowCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制台结果收集回调（M2-04）。
 *
 * <p>作为网关 CollectingRowCallback 的内层委托，收集列头与行到内存列表供同步返回。
 * 客户端上限（默认 500）达到时返回 false 终止读取；网关外层再施 5000 行/50MB 硬上限。</p>
 *
 * @author DataGate
 */
public class ConsoleResultCollector implements RowCallback {

    private final long maxRows;
    private RowHeader header;
    private final List<List<RowCell>> rows = new ArrayList<>();

    public ConsoleResultCollector(long maxRows) {
        this.maxRows = maxRows;
    }

    @Override
    public void onHeader(RowHeader header) {
        this.header = header;
    }

    @Override
    public boolean onRow(List<RowCell> cells) {
        if (rows.size() >= maxRows) {
            return false;
        }
        rows.add(new ArrayList<>(cells));
        return true;
    }

    @Override
    public void onComplete() {
        // 同步返回，无需 flush
    }

    public RowHeader header() {
        return header;
    }

    public List<List<RowCell>> rows() {
        return rows;
    }
}
