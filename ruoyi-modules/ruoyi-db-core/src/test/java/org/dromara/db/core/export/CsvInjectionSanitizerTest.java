package org.dromara.db.core.export;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV 公式注入防护单元测试（docs/06 §12、docs/08 §10）。
 *
 * @author DataGate
 */
@Tag("unit")
class CsvInjectionSanitizerTest {

    @Test
    void prepends_quote_for_formula_triggers() {
        assertEquals("'=SUM(A1:A2)", CsvInjectionSanitizer.sanitizeCell("=SUM(A1:A2)"));
        assertEquals("'+1+1", CsvInjectionSanitizer.sanitizeCell("+1+1"));
        assertEquals("'-1", CsvInjectionSanitizer.sanitizeCell("-1"));
        assertEquals("'@cmd", CsvInjectionSanitizer.sanitizeCell("@cmd"));
        assertEquals("'\t=1", CsvInjectionSanitizer.sanitizeCell("\t=1"));
    }

    @Test
    void safe_values_passthrough() {
        assertEquals("alice", CsvInjectionSanitizer.sanitizeCell("alice"));
        assertEquals("138****5678", CsvInjectionSanitizer.sanitizeCell("138****5678"));
        assertEquals("100", CsvInjectionSanitizer.sanitizeCell("100"));
        assertEquals("a@b.com", CsvInjectionSanitizer.sanitizeCell("a@b.com")); // @ 不在首位
    }

    @Test
    void null_becomes_empty() {
        assertEquals("", CsvInjectionSanitizer.sanitizeCell(null));
    }

    @Test
    void strips_cr_lf_and_sanitizes() {
        // 含换行先剥离为空格，再判首字符（CRLF 各替换为空格）
        String out = CsvInjectionSanitizer.sanitizeCell("=1\r\n2");
        assertTrue(out.startsWith("'=1"), out);
        assertFalse(out.contains("\r"), out);
        assertFalse(out.contains("\n"), out);
        // 非首字符触发，剥离 CR 后正常文本
        assertEquals("plain text", CsvInjectionSanitizer.sanitizeCell("plain\rtext"));
    }

    @Test
    void empty_string_passthrough() {
        assertEquals("", CsvInjectionSanitizer.sanitizeCell(""));
    }

    @Test
    void sanitize_row_applies_per_cell() {
        List<String> out = CsvInjectionSanitizer.sanitizeRow(List.of("ok", "=evil", "138****5678"));
        assertEquals(List.of("ok", "'=evil", "138****5678"), out);
    }

    @Test
    void sanitize_row_null_and_empty_safe() {
        assertEquals(List.of(), CsvInjectionSanitizer.sanitizeRow(null));
        assertEquals(List.of(), CsvInjectionSanitizer.sanitizeRow(List.of()));
    }

    @Test
    void masked_null_placeholder_empty() {
        // 脱敏 HIDDEN 单元格 value=null → CSV 空
        assertEquals("", CsvInjectionSanitizer.sanitizeCell(null));
    }
}
