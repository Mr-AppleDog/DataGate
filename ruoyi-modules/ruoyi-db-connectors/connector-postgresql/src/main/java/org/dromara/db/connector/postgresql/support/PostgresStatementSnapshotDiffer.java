package org.dromara.db.connector.postgresql.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * pg_stat_statements 摘要快照差值（docs/07 §4.3：保存 calls/total_exec_time/rows 快照，差值生成时间桶，标 AGGREGATED）。
 * 纯算法，不连数据库，可单测。stats_reset 或计数回退视为重置，重建基线不产出负指标。
 *
 * @author DataGate
 */
public final class PostgresStatementSnapshotDiffer {

    private PostgresStatementSnapshotDiffer() {
    }

    /**
     * 单条语句快照（当前轮询值）。key = queryid:dbid:userid。
     */
    public record PgSnapshot(String key, String query, String databaseName,
                              long calls, long totalExecTimeMicros, long rows) {
    }

    public record PgDiff(String key, String query, String databaseName,
                          long calls, long totalExecTimeMicros, long rows, boolean reset) {
    }

    public static List<PgDiff> diff(Map<String, PgSnapshot> current,
                                     Map<String, PgSnapshot> previous) {
        List<PgDiff> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        Map<String, PgSnapshot> prev = previous == null ? Map.of() : previous;
        for (Map.Entry<String, PgSnapshot> e : current.entrySet()) {
            PgSnapshot cur = e.getValue();
            PgSnapshot p = prev.get(e.getKey());
            if (p == null) {
                out.add(new PgDiff(cur.key(), cur.query(), cur.databaseName(),
                    cur.calls(), cur.totalExecTimeMicros(), cur.rows(), false));
            } else {
                long dCalls = cur.calls() - p.calls();
                if (dCalls < 0) {
                    out.add(new PgDiff(cur.key(), cur.query(), cur.databaseName(),
                        0, 0, 0, true));
                } else if (dCalls > 0) {
                    out.add(new PgDiff(cur.key(), cur.query(), cur.databaseName(),
                        dCalls,
                        cur.totalExecTimeMicros() - p.totalExecTimeMicros(),
                        cur.rows() - p.rows(), false));
                }
            }
        }
        return out;
    }
}
