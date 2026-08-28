package org.dromara.db.observability.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性分析与规则化建议测试（docs/07 §11）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("慢查询确定性分析")
class DeterministicAnalyzerTest {

    private DeterministicAnalyzer.AnalysisResult analyze(String sql, long count, long total,
                                                            Long examined, Long returned, Long lock, boolean firstSeen) {
        return DeterministicAnalyzer.analyze(count, total, total, total, lock, examined, returned, firstSeen, false, sql);
    }

    @Test
    @DisplayName("SELECT * + 无 WHERE 触发对应建议")
    void selectStarNoWhere() {
        var r = analyze("SELECT * FROM orders", 200, 50_000_000L, 10000L, 100L, null, false);
        assertTrue(r.riskFlags().contains("SELECT_STAR"));
        assertTrue(r.riskFlags().contains("NO_WHERE_CLAUSE"));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("SELECT *")));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("WHERE")));
        assertTrue(r.tables().contains("orders"));
    }

    @Test
    @DisplayName("高扫描/返回比触发索引建议")
    void highScanReturn() {
        var r = analyze("SELECT id FROM t WHERE name = ?", 10, 1_000_000L, 50000L, 100L, null, false);
        assertTrue(r.riskFlags().contains("HIGH_SCAN_RETURN_RATIO"));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("扫描/返回比")));
    }

    @Test
    @DisplayName("锁等待高触发锁竞争建议")
    void highLockWait() {
        var r = analyze("UPDATE t SET v = ? WHERE id = ?", 5, 2_000_000L, 1000L, 1L, 2_000_000L, false);
        assertTrue(r.riskFlags().contains("HIGH_LOCK_WAIT"));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("锁等待")));
    }

    @Test
    @DisplayName("首次出现 + 突增触发关注建议")
    void firstSeenAndSurge() {
        var r = DeterministicAnalyzer.analyze(50, 30_000_000L, 0, 0, null, null, null, true, true, "SELECT * FROM t WHERE id = ?");
        assertTrue(r.riskFlags().contains("FIRST_SEEN"));
        assertTrue(r.riskFlags().contains("SURGE"));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("首次出现")));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("突增")));
    }

    @Test
    @DisplayName("频次+总耗时双高触发缓存建议")
    void highFrequencyAndTotal() {
        var r = analyze("SELECT * FROM t WHERE id = ?", 500, 60_000_000L, 100L, 5L, null, false);
        assertTrue(r.riskFlags().contains("HIGH_FREQUENCY"));
        assertTrue(r.riskFlags().contains("HIGH_TOTAL_DURATION"));
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("缓存")));
    }

    @Test
    @DisplayName("无风险时给默认建议")
    void noRiskDefault() {
        var r = analyze("SELECT id FROM t WHERE id = ? LIMIT 1", 2, 100_000L, 1L, 1L, null, false);
        assertTrue(r.suggestions().stream().anyMatch(s -> s.contains("安全 EXPLAIN")));
    }
}
