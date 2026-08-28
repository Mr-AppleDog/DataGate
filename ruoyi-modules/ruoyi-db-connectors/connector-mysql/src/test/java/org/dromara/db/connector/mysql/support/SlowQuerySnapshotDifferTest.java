package org.dromara.db.connector.mysql.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * performance_schema 快照差值测试（docs/07 §4.2：差值生成时间桶，COUNT 回退重建基线）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("performance_schema 快照差值")
class SlowQuerySnapshotDifferTest {

    private SlowQuerySnapshotDiffer.DigestSnapshot snap(String digest, long count, long timer, long rows) {
        return new SlowQuerySnapshotDiffer.DigestSnapshot(digest, "SELECT * FROM t WHERE id = ?", "db1",
            count, timer, 0, rows, 0, 1000L, 2000L);
    }

    @Test
    @DisplayName("首次发现：previous 无则产出当前全量")
    void firstSeenEmitsFull() {
        var current = Map.of("d1", snap("d1", 5, 5000, 50));
        var diffs = SlowQuerySnapshotDiffer.diff(current, Map.of());
        assertEquals(1, diffs.size());
        var d = diffs.get(0);
        assertEquals(5, d.count());
        assertEquals(5000, d.timer());
        assertFalse(d.reset());
    }

    @Test
    @DisplayName("计数增加：产出差值")
    void incrementEmitsDiff() {
        var prev = Map.of("d1", snap("d1", 5, 5000, 50));
        var current = Map.of("d1", snap("d1", 8, 8000, 80));
        var diffs = SlowQuerySnapshotDiffer.diff(current, prev);
        assertEquals(1, diffs.size());
        var d = diffs.get(0);
        assertEquals(3, d.count());
        assertEquals(3000, d.timer());
        assertEquals(30, d.rowsExamined());
        assertFalse(d.reset());
    }

    @Test
    @DisplayName("COUNT 回退：reset=true 不产出负指标")
    void resetDetected() {
        var prev = Map.of("d1", snap("d1", 10, 10000, 100));
        var current = Map.of("d1", snap("d1", 5, 5000, 50));
        var diffs = SlowQuerySnapshotDiffer.diff(current, prev);
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).reset());
        assertEquals(0, diffs.get(0).count());
    }

    @Test
    @DisplayName("计数不变：跳过不产出")
    void noChangeSkipped() {
        var prev = Map.of("d1", snap("d1", 5, 5000, 50));
        var current = Map.of("d1", snap("d1", 5, 5000, 50));
        var diffs = SlowQuerySnapshotDiffer.diff(current, prev);
        assertTrue(diffs.isEmpty());
    }

    @Test
    @DisplayName("空 current：无产出")
    void emptyCurrentNoDiff() {
        var diffs = SlowQuerySnapshotDiffer.diff(Map.of(), Map.of("d1", snap("d1", 5, 5000, 50)));
        assertTrue(diffs.isEmpty());
    }
}
