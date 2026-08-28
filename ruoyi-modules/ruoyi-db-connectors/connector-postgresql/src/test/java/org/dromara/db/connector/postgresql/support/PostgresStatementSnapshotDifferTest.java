package org.dromara.db.connector.postgresql.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pg_stat_statements 快照差值测试（docs/07 §4.3：差值生成时间桶，stats_reset/计数回退重建基线）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("pg_stat_statements 快照差值")
class PostgresStatementSnapshotDifferTest {

    private PostgresStatementSnapshotDiffer.PgSnapshot snap(String key, long calls, long execMicros, long rows) {
        return new PostgresStatementSnapshotDiffer.PgSnapshot(key, "SELECT * FROM t WHERE id = $1", "db1",
            calls, execMicros, rows);
    }

    @Test
    @DisplayName("首次发现：previous 无则产出当前全量")
    void firstSeenEmitsFull() {
        var current = Map.of("k1", snap("k1", 5, 5000, 50));
        var diffs = PostgresStatementSnapshotDiffer.diff(current, Map.of());
        assertEquals(1, diffs.size());
        var d = diffs.get(0);
        assertEquals(5, d.calls());
        assertEquals(5000, d.totalExecTimeMicros());
        assertFalse(d.reset());
    }

    @Test
    @DisplayName("calls 增加：产出差值")
    void incrementEmitsDiff() {
        var prev = Map.of("k1", snap("k1", 5, 5000, 50));
        var current = Map.of("k1", snap("k1", 8, 8000, 80));
        var diffs = PostgresStatementSnapshotDiffer.diff(current, prev);
        assertEquals(1, diffs.size());
        var d = diffs.get(0);
        assertEquals(3, d.calls());
        assertEquals(3000, d.totalExecTimeMicros());
        assertEquals(30, d.rows());
        assertFalse(d.reset());
    }

    @Test
    @DisplayName("calls 回退：reset=true 不产出负指标")
    void resetDetected() {
        var prev = Map.of("k1", snap("k1", 10, 10000, 100));
        var current = Map.of("k1", snap("k1", 5, 5000, 50));
        var diffs = PostgresStatementSnapshotDiffer.diff(current, prev);
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).reset());
        assertEquals(0, diffs.get(0).calls());
    }

    @Test
    @DisplayName("calls 不变：跳过不产出")
    void noChangeSkipped() {
        var prev = Map.of("k1", snap("k1", 5, 5000, 50));
        var current = Map.of("k1", snap("k1", 5, 5000, 50));
        var diffs = PostgresStatementSnapshotDiffer.diff(current, prev);
        assertTrue(diffs.isEmpty());
    }

    @Test
    @DisplayName("空 current：无产出")
    void emptyCurrentNoDiff() {
        var diffs = PostgresStatementSnapshotDiffer.diff(Map.of(), Map.of("k1", snap("k1", 5, 5000, 50)));
        assertTrue(diffs.isEmpty());
    }
}
