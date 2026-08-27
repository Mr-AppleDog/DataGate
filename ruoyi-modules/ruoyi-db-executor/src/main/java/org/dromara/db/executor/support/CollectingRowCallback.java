package org.dromara.db.executor.support;

import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.spi.RowCallback;

import java.util.List;

/**
 * 收集型行回调（docs/06 第 11 节）。
 *
 * <p>包装客户端回调，在连接器流式回吐之上施加行/字节硬上限（纵深防御——
 * 即使连接器实现有缺陷也不超出平台硬上限），并收集 rowCount/resultBytes/truncated
 * 供查询审计（审计只记规模，不记正文）。</p>
 *
 * @author DataGate
 */
public class CollectingRowCallback implements RowCallback {

    private final RowCallback delegate;
    private final long hardMaxRows;
    private final long hardMaxBytes;

    private long rowCount;
    private long resultBytes;
    private boolean truncated;
    private boolean stopped;

    public CollectingRowCallback(RowCallback delegate, long hardMaxRows, long hardMaxBytes) {
        this.delegate = delegate;
        this.hardMaxRows = hardMaxRows;
        this.hardMaxBytes = hardMaxBytes;
    }

    @Override
    public void onHeader(RowHeader header) {
        delegate.onHeader(header);
    }

    @Override
    public boolean onRow(List<RowCell> cells) {
        if (stopped) {
            return false;
        }
        if (rowCount >= hardMaxRows) {
            truncated = true;
            stopped = true;
            return false;
        }
        long rowBytes = estimateRowBytes(cells);
        if (resultBytes + rowBytes > hardMaxBytes) {
            truncated = true;
            stopped = true;
            return false;
        }
        rowCount++;
        resultBytes += rowBytes;
        return delegate.onRow(cells);
    }

    @Override
    public void onComplete() {
        delegate.onComplete();
    }

    @Override
    public boolean onError(Throwable t) {
        return delegate.onError(t);
    }

    public long rowCount() {
        return rowCount;
    }

    public long resultBytes() {
        return resultBytes;
    }

    public boolean truncated() {
        return truncated;
    }

    private static long estimateRowBytes(List<RowCell> cells) {
        long bytes = 0;
        for (RowCell cell : cells) {
            if (cell.value() != null) {
                bytes += cell.value().length();
            }
            if (cell.binarySummary() != null) {
                bytes += cell.binarySummary().length();
            }
        }
        return bytes;
    }
}
