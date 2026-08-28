package org.dromara.db.observability.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 慢查询确定性分析与规则化建议（docs/07 §11）。
 * 纯算法，不连数据库，可单测。P0 提供确定性分析 + 常见建议模板，不得声称建议一定正确。
 *
 * @author DataGate
 */
public final class DeterministicAnalyzer {

    private DeterministicAnalyzer() {
    }

    private static final Pattern FROM_TABLE = Pattern.compile(
        "(?i)\\b(?:FROM|JOIN)\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern SELECT_STAR = Pattern.compile("(?i)\\bSELECT\\s+\\*");

    /**
     * @param eventCount          窗内事件数
     * @param totalDurationMicros 窗内总耗时微秒
     * @param p95Micros           P95 微秒
     * @param maxMicros           单次最大微秒
     * @param lockWaitMicros      锁等待微秒（可空=缺失）
     * @param rowsExamined        扫描行（可空）
     * @param rowsReturned        返回行（可空）
     * @param firstSeen           是否首次出现
     * @param surge               是否突增（2x 历史）
     * @param normalizedStatement 归一化语句（脱敏，含 ? 占位）
     */
    public record AnalysisResult(
        long eventCount, long totalDurationMicros, long p95Micros, long maxMicros,
        Long lockWaitMicros, Long rowsExamined, Long rowsReturned,
        double scanReturnRatio, boolean firstSeen, boolean surge,
        List<String> riskFlags, List<String> suggestions, List<String> tables
    ) {
    }

    public static AnalysisResult analyze(long eventCount, long totalDurationMicros, long p95Micros,
                                          long maxMicros, Long lockWaitMicros, Long rowsExamined,
                                          Long rowsReturned, boolean firstSeen, boolean surge,
                                          String normalizedStatement) {
        List<String> riskFlags = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        String sql = normalizedStatement == null ? "" : normalizedStatement;
        String upper = sql.toUpperCase();

        // 频次高（>100/窗）
        boolean highFrequency = eventCount > 100;
        // 总耗时高（>10s/窗）
        boolean highTotal = totalDurationMicros > 10_000_000L;
        // 锁等待高（>1s/窗）
        boolean highLockWait = lockWaitMicros != null && lockWaitMicros > 1_000_000L;
        // 扫描/返回比高
        double scanReturnRatio = 0;
        boolean highScanReturn = false;
        if (rowsExamined != null && rowsReturned != null && rowsReturned > 0) {
            scanReturnRatio = rowsExamined.doubleValue() / rowsReturned;
            highScanReturn = scanReturnRatio > 10;
        }
        boolean selectStar = SELECT_STAR.matcher(sql).find();
        boolean hasWhere = upper.contains(" WHERE ");
        boolean hasLimit = upper.contains(" LIMIT ");

        if (highFrequency) riskFlags.add("HIGH_FREQUENCY");
        if (highTotal) riskFlags.add("HIGH_TOTAL_DURATION");
        if (highLockWait) riskFlags.add("HIGH_LOCK_WAIT");
        if (highScanReturn) riskFlags.add("HIGH_SCAN_RETURN_RATIO");
        if (firstSeen) riskFlags.add("FIRST_SEEN");
        if (surge) riskFlags.add("SURGE");
        if (selectStar) riskFlags.add("SELECT_STAR");
        if (hasWhere && !hasLimit) {
            // 有 WHERE 但无 LIMIT，可能无界
        } else if (!hasWhere && upper.contains(" FROM ")) {
            riskFlags.add("NO_WHERE_CLAUSE");
        }

        // 常见建议模板
        if (selectStar) suggestions.add("减少 SELECT *，按需取列以降低扫描与网络开销");
        if (!hasWhere && upper.contains(" FROM ")) suggestions.add("缺少 WHERE 过滤，检查过滤列是否有索引");
        if (highScanReturn) suggestions.add("扫描/返回比高（" + Math.round(scanReturnRatio) + "x），检查索引覆盖与 WHERE 条件");
        if (highLockWait) suggestions.add("锁等待高，检查事务粒度与行锁竞争");
        if (!hasLimit && !hasWhere && upper.contains(" FROM ")) suggestions.add("避免无界分页，加 LIMIT 约束结果集");
        if (highFrequency && highTotal) suggestions.add("频次与总耗时双高，考虑缓存或批处理减少调用");
        if (firstSeen) suggestions.add("首次出现，关注是否新引入或新上线变更");
        if (surge) suggestions.add("相比历史突增，排查是否数据量变化或索引退化");
        if (suggestions.isEmpty()) suggestions.add("暂无明显风险，建议结合安全 EXPLAIN 进一步分析");

        return new AnalysisResult(eventCount, totalDurationMicros, p95Micros, maxMicros,
            lockWaitMicros, rowsExamined, rowsReturned, scanReturnRatio, firstSeen, surge,
            riskFlags, suggestions, extractTables(sql));
    }

    private static List<String> extractTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = FROM_TABLE.matcher(sql);
        while (m.find()) {
            String t = m.group(1);
            if (!tables.contains(t)) {
                tables.add(t);
            }
        }
        return tables;
    }
}
