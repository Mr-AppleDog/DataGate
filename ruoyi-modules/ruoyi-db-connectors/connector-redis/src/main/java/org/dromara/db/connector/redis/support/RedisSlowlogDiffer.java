package org.dromara.db.connector.redis.support;

import org.dromara.db.core.domain.SlowQueryRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis SLOWLOG 差值（docs/07 §4.4：实例启动纪元 + slowlog id 游标，id 回退/RESET 重建基线）。
 * 纯算法，不连 Redis，可单测。每条慢日志是逐次事件（COMPLETE，非 AGGREGATED）。
 *
 * @author DataGate
 */
public final class RedisSlowlogDiffer {

    private RedisSlowlogDiffer() {
    }

    /**
     * 单条 SLOWLOG 条目（已解析）。
     *
     * @param id            slowlog id（单调递增，RESET/重启回退）
     * @param timestampSec   发生时间（Unix 秒）
     * @param durationMicros 执行耗时（微秒）
     * @param verb          命令名（大写）
     * @param argc           参数个数（不含命令名）
     */
    public record SlowlogEntry(long id, long timestampSec, long durationMicros, String verb, int argc) {
    }

    /**
     * @param records    新产出记录（id > lastId，模板化不存 value）
     * @param newLastId  新游标（本次最大 id）
     * @param reset      是否检测到 RESET/重启（id 回退）
     */
    public record DiffResult(List<SlowQueryRecord> records, long newLastId, boolean reset) {
    }

    /**
     * 从 SLOWLOG 条目中筛选 id > lastId 的新事件。
     * 若 maxId < lastId（RESET/重启）则重建基线（lastId=0，全部当新事件）。
     */
    public static DiffResult collect(List<SlowlogEntry> entries, long lastId, Instant now) {
        long maxId = 0;
        for (SlowlogEntry e : entries) {
            if (e.id() > maxId) {
                maxId = e.id();
            }
        }
        boolean reset = !entries.isEmpty() && maxId < lastId;
        long effectiveLast = reset ? 0L : lastId;
        List<SlowQueryRecord> records = new ArrayList<>();
        for (SlowlogEntry e : entries) {
            if (e.id() <= effectiveLast) {
                continue;
            }
            String normalized = e.verb() + " [argc=" + e.argc() + "]";
            String fingerprint = sha256(normalized);
            records.add(new SlowQueryRecord(
                "slowlog:" + e.id(),
                String.valueOf(e.id()),
                null,
                "REDIS",
                null,
                fingerprint,
                null,
                "redis-slowlog",
                normalized,
                normalized,
                Instant.ofEpochSecond(e.timestampSec()),
                now,
                e.durationMicros(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "COMPLETE"
            ));
        }
        long newLastId = Math.max(maxId, effectiveLast);
        return new DiffResult(records, newLastId, reset);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }
}
