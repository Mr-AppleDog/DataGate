package org.dromara.db.executor.support;

import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出 CSV 回调单元测试（docs/06 §12、docs/08 §10）。
 *
 * @author DataGate
 */
@Tag("unit")
class CsvExportRowCallbackTest {

    @Test
    void csv_cell_sanitizes_injection() {
        assertEquals("'=evil", CsvExportRowCallback.csvCell("=evil"));
        assertEquals("'+1", CsvExportRowCallback.csvCell("+1"));
        assertEquals("plain", CsvExportRowCallback.csvCell("plain"));
        assertEquals("", CsvExportRowCallback.csvCell(null));
    }

    @Test
    void csv_cell_quotes_comma() {
        assertEquals("\"a,b\"", CsvExportRowCallback.csvCell("a,b"));
    }

    @Test
    void csv_cell_quotes_and_doubles_quote() {
        assertEquals("\"a\"\"b\"", CsvExportRowCallback.csvCell("a\"b"));
    }

    @Test
    void writes_header_and_rows_with_crlf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvExportRowCallback cb = new CsvExportRowCallback(out, Long.MAX_VALUE);
        cb.onHeader(new RowHeader(List.of(new ColumnMeta("name", "VARCHAR", "text"), new ColumnMeta("phone", "VARCHAR", "text"))));
        cb.onRow(List.of(new RowCell("alice", false, null), new RowCell("=evil", false, null)));
        cb.onRow(List.of(new RowCell("bob", false, null), new RowCell("138****5678", false, null)));
        cb.onComplete();
        String csv = out.toString(StandardCharsets.UTF_8);
        // 列头未触发注入，直接写出
        assertTrue(csv.startsWith("name,phone\r\n"), csv);
        // 第一行 phone 值 =evil 被前置 '
        assertTrue(csv.contains("alice,'=evil\r\n"), csv);
        assertTrue(csv.contains("bob,138****5678\r\n"), csv);
        assertEquals(2, cb.rowCount());
        assertTrue(cb.bytes() > 0);
    }

    @Test
    void truncates_when_max_bytes_exceeded() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvExportRowCallback cb = new CsvExportRowCallback(out, 5); // 极小上限
        cb.onHeader(new RowHeader(List.of(new ColumnMeta("c", "VARCHAR", "text"))));
        boolean cont = true;
        for (int i = 0; i < 100 && cont; i++) {
            cont = cb.onRow(List.of(new RowCell("xxxxxxxxxx", false, null)));
        }
        // 达上限后停止（onRow 返回 false 或后续不再追加）
        assertTrue(cb.bytes() <= 200, "bytes=" + cb.bytes());
    }
}
