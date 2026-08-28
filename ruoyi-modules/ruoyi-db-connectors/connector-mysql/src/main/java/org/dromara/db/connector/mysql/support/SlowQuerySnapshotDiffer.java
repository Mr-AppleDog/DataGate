package org.dromara.db.connector.mysql.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * performance_schema 摘要快照差值（docs/07 §4.2：保存计数快照，差值生成时间桶，标 AGGREGATED）。
 * 纯算法，不连数据库，可单测。
 *
 * @author DataGate
 */
public final class SlowQuerySnapshotDiffer {

    private SlowQuerySnapshotDiffer() {
    }

    /**
     * 单条摘要快照（当前轮询值）。
     *
     * @param digest          MySQL DIGEST 哈希
     * @param digestText      MySQL DIGEST_TEXT（已参数化）
     * @param schema          SCHEMA_NAME
     * @param count           COUNT_STAR
     * @param timer           SUM_TIMER_WAIT（皮秒）
     * @param lockTime        SUM_LOCK_TIME（皮秒）
     * @param rowsExamined    SUM_ROWS_EXAMINED
     * @param rowsSent        SUM_ROWS_SENT
     * @param firstSeenMillis FIRST_SEEN 毫秒
     * @param lastSeenMillis  LAST_SEEN 毫秒
     */
    public record DigestSnapshot(String digest, String digestText, String schema,
                                 long count, long timer, long lockTime,
                                 long rowsExamined, long rowsSent,
                                 long firstSeenMillis, long lastSeenMillis) {
    }

    /**
     * 差值结果。
     *
     * @param reset 是否检测到统计重置（COUNT_STAR 回退，需重建基线，本轮不产出）
     */
    public record DigestDiff(String digest, String digestText, String schema,
                              long count, long timer, long lockTime,
                              long rowsExamined, long rowsSent,
                              long firstSeenMillis, long lastSeenMillis, boolean reset) {
    }

    /**
     * 计算当前快照与上一快照的差值。
     * - 新摘要（previous 无）：产出当前全量（首次发现）；
     * - count 回退：reset=true，调用方重建基线，本轮不产出事件；
     * - count 不变：跳过；
     * - count 增加：产出差值。
     */
    public static List<DigestDiff> diff(Map<String, DigestSnapshot> current,
                                        Map<String, DigestSnapshot> previous) {
        List<DigestDiff> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        Map<String, DigestSnapshot> prev = previous == null ? Map.of() : previous;
        for (Map.Entry<String, DigestSnapshot> e : current.entrySet()) {
            DigestSnapshot cur = e.getValue();
            DigestSnapshot p = prev.get(e.getKey());
            if (p == null) {
                out.add(new DigestDiff(cur.digest(), cur.digestText(), cur.schema(),
                    cur.count(), cur.timer(), cur.lockTime(),
                    cur.rowsExamined(), cur.rowsSent(),
                    cur.firstSeenMillis(), cur.lastSeenMillis(), false));
            } else {
                long dCount = cur.count() - p.count();
                if (dCount < 0) {
                    out.add(new DigestDiff(cur.digest(), cur.digestText(), cur.schema(),
                        0, 0, 0, 0, 0, cur.firstSeenMillis(), cur.lastSeenMillis(), true));
                } else if (dCount > 0) {
                    out.add(new DigestDiff(cur.digest(), cur.digestText(), cur.schema(),
                        dCount,
                        cur.timer() - p.timer(),
                        cur.lockTime() - p.lockTime(),
                        cur.rowsExamined() - p.rowsExamined(),
                        cur.rowsSent() - p.rowsSent(),
                        cur.firstSeenMillis(), cur.lastSeenMillis(), false));
                }
            }
        }
        return out;
    }
}
