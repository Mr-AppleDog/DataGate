package org.dromara.db.core.export;

/**
 * CSV 公式注入防护（docs/06 §12、docs/08 §10）。
 *
 * <p>CSV 中以 =、+、-、@、TAB、CR 开头或含 CR/LF 的单元格，可被电子表格解释为公式/命令。
 * 防护策略：以危险字符开头的单元格前置单引号 {@code '}（电子表格视为文本）；
 * 含 CR/LF 的单元格先剥离控制字符再前置。null 透传为空。永不抛异常（失败关闭=前置单引号）。</p>
 *
 * @author DataGate
 */
public final class CsvInjectionSanitizer {

    private CsvInjectionSanitizer() {
    }

    /**
     * 单元格安全化。返回可直接写入 CSV 的文本值。
     */
    public static String sanitizeCell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        // 剥离 CR/LF（防止换行注入破坏 CSV 结构与公式注入）
        if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
            v = v.replace("\r", " ").replace("\n", " ");
        }
        if (v.isEmpty()) {
            return "";
        }
        char first = v.charAt(0);
        if (isFormulaTrigger(first)) {
            return "'" + v;
        }
        return v;
    }

    /**
     * 整行安全化（按列顺序）。返回新列表。
     */
    public static java.util.List<String> sanitizeRow(java.util.List<String> cells) {
        if (cells == null || cells.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>(cells.size());
        for (String c : cells) {
            out.add(sanitizeCell(c));
        }
        return out;
    }

    /**
     * 是否公式触发字符（=、+、-、@、TAB）。
     */
    static boolean isFormulaTrigger(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t';
    }
}
