package org.dromara.db.executor.support;

import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.export.CsvInjectionSanitizer;
import org.dromara.db.core.spi.RowCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导出 CSV 行回调（docs/06 §12、docs/08 §10）。
 *
 * <p>流式写入 CSV：列头 + 行，每单元格先经 {@link CsvInjectionSanitizer} 防公式注入，
 * 再按 RFC4180 引用（含逗号/引号/换行则包裹并双写引号）。行/字节计数，超 maxBytes 截断（返回 false）。
 * 不持久保存结果正文于内存——直接写 OutputStream（临时文件/加密管道）。</p>
 *
 * @author DataGate
 */
public class CsvExportRowCallback implements RowCallback {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);

    private final OutputStream out;
    private final long maxBytes;
    private long bytes;
    private long rowCount;
    private boolean truncated;
    private boolean headerWritten;

    public CsvExportRowCallback(OutputStream out, long maxBytes) {
        this.out = out;
        this.maxBytes = maxBytes <= 0 ? Long.MAX_VALUE : maxBytes;
    }

    @Override
    public void onHeader(RowHeader header) {
        if (headerWritten || header == null || header.columns() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < header.columns().size(); i++) {
            ColumnMeta col = header.columns().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvCell(col == null ? "" : col.name()));
        }
        writeLine(sb.toString());
        headerWritten = true;
    }

    @Override
    public boolean onRow(List<RowCell> cells) {
        if (!headerWritten) {
            // 执行器保证先 onHeader；防御性补写空头
            onHeader(new RowHeader(java.util.List.of()));
        }
        StringBuilder sb = new StringBuilder();
        if (cells != null) {
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                RowCell c = cells.get(i);
                String v = (c == null || c.value() == null) ? "" : c.value();
                sb.append(csvCell(v));
            }
        }
        writeLine(sb.toString());
        rowCount++;
        if (bytes > maxBytes) {
            truncated = true;
            return false;
        }
        return true;
    }

    @Override
    public void onComplete() {
        try {
            out.flush();
        } catch (IOException ignored) {
            // best effort
        }
    }

    public long rowCount() {
        return rowCount;
    }

    public long bytes() {
        return bytes;
    }

    public boolean truncated() {
        return truncated;
    }

    // ====================== 内部 ======================

    private void writeLine(String line) {
        try {
            byte[] b = line.getBytes(StandardCharsets.UTF_8);
            out.write(b);
            out.write(CRLF);
            bytes += b.length + CRLF.length;
        } catch (IOException e) {
            truncated = true;
        }
    }

    /** 单元格安全化 + RFC4180 引用。 */
    static String csvCell(String value) {
        String safe = CsvInjectionSanitizer.sanitizeCell(value);
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\r") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
