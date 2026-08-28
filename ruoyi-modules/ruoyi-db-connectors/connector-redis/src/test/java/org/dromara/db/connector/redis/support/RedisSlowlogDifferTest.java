package org.dromara.db.connector.redis.support;

import org.dromara.db.connector.redis.support.RedisSlowlogDiffer.SlowlogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis SLOWLOG 差值测试（docs/07 §4.4：slowlog id 游标，id 回退/RESET 重建基线）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("Redis SLOWLOG 差值")
class RedisSlowlogDifferTest {

    private SlowlogEntry e(long id, long durationMicros, String verb, int argc) {
        return new SlowlogEntry(id, 1700000000L + id, durationMicros, verb, argc);
    }

    @Test
    @DisplayName("首次轮询（lastId=0）：全部产出，新游标=maxId")
    void firstPollEmitsAll() {
        var entries = List.of(e(3, 1000, "GET", 1), e(5, 2000, "SET", 2));
        var res = RedisSlowlogDiffer.collect(entries, 0L, Instant.now());
        assertEquals(2, res.records().size());
        assertEquals(5, res.newLastId());
        assertFalse(res.reset());
    }

    @Test
    @DisplayName("增量轮询：只产出 id > lastId 的新事件")
    void incrementalEmitsOnlyNew() {
        var entries = List.of(e(4, 1000, "GET", 1), e(5, 2000, "SET", 2));
        var res = RedisSlowlogDiffer.collect(entries, 4L, Instant.now());
        assertEquals(1, res.records().size(), "id=4 应跳过");
        assertEquals(5, res.newLastId());
    }

    @Test
    @DisplayName("id 回退（RESET/重启）：重建基线，全部当新事件")
    void resetEmitsAll() {
        var entries = List.of(e(1, 1000, "GET", 1), e(2, 2000, "SET", 2));
        var res = RedisSlowlogDiffer.collect(entries, 5L, Instant.now());
        assertTrue(res.reset());
        assertEquals(2, res.records().size());
        assertEquals(2, res.newLastId());
    }

    @Test
    @DisplayName("无新事件（id <= lastId）：跳过")
    void noNewSkipped() {
        var entries = List.of(e(3, 1000, "GET", 1));
        var res = RedisSlowlogDiffer.collect(entries, 3L, Instant.now());
        assertTrue(res.records().isEmpty());
        assertEquals(3, res.newLastId());
    }

    @Test
    @DisplayName("命令模板化不保存 value/key")
    void commandTemplateNoValue() {
        var entries = List.of(e(1, 1000, "SET", 2));
        var res = RedisSlowlogDiffer.collect(entries, 0L, Instant.now());
        var r = res.records().get(0);
        assertEquals("SET [argc=2]", r.normalizedStatement());
        assertEquals("SET [argc=2]", r.sanitizedSample());
        assertFalse(r.normalizedStatement().contains("mykey"), "命令模板不得含 key 明文");
    }
}
