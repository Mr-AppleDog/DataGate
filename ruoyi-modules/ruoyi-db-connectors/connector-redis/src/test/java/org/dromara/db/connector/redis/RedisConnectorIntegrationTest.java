package org.dromara.db.connector.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.RowCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 连接器真实引擎集成测试（docs/06 §8、§15、§16）。
 *
 * <p>连接 VM {@code 192.168.149.128} Redis 8.2（AGENTS.md §5 文档化测试凭据）。
 * 覆盖 §16 验收：安全读命令返回值、强制拒绝语料零写入（KEYS/EVAL/SET/FLUSHDB）、
 * MGET 截断、SCAN 强制 COUNT。凭据经系统属性可覆盖。</p>
 *
 * <p>仅 {@code -DtestTags=integration} 触发；默认构建（{@code unit}）不运行。</p>
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("Redis 连接器真实引擎集成测试 (§16)")
class RedisConnectorIntegrationTest {

    private static final String HOST = System.getProperty("datagate.redis.host", "192.168.149.128");
    private static final int PORT = Integer.getInteger("datagate.redis.port", 6379);
    private static final String PASS = System.getProperty("datagate.redis.pass", "mrlu");
    private static final String DB = System.getProperty("datagate.redis.db", "0");

    private final RedisQueryParser parser = new RedisQueryParser();
    private final RedisQueryExecutor executor = new RedisQueryExecutor(parser);

    @BeforeAll
    static void setupKeys() {
        try (RedisClient client = RedisClient.create(uri());
             StatefulRedisConnection<String, String> c = client.connect(StringCodec.UTF8)) {
            RedisCommands<String, String> s = c.sync();
            s.flushdb();
            s.set("user:1", "alice");
            s.set("user:2", "bob");
            s.set("order:1", "o1");
            s.hset("h:1", "f1", "v1");
            s.hset("h:1", "f2", "v2");
            for (int i = 0; i < 5; i++) {
                s.rpush("l:1", "elem" + i);
            }
            s.sadd("s:1", "m1", "m2", "m3");
            s.zadd("z:1", 1.0, "a");
            s.zadd("z:1", 2.0, "b");
            s.zadd("z:1", 3.0, "c");
        }
    }

    @AfterAll
    static void teardownKeys() {
        try (RedisClient client = RedisClient.create(uri());
             StatefulRedisConnection<String, String> c = client.connect(StringCodec.UTF8)) {
            c.sync().flushdb();
        }
    }

    private static RedisURI uri() {
        RedisURI u = RedisURI.builder().withHost(HOST).withPort(PORT)
            .withDatabase(Integer.parseInt(DB))
            .withTimeout(Duration.ofSeconds(5)).build();
        u.setPassword(PASS);
        return u;
    }

    // ====================== 夹具 ======================

    private ConnectionProfile profile() {
        return new ConnectionProfile(HOST, PORT, DB, "default", Map.of(), TlsMode.DISABLE,
            Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private ExecutionPlan plan(long maxRows, long maxBytes) {
        return ExecutionPlan.of("p1", 1L, 100L, DB, null, "h", "GET user:1", "GET",
            List.of(1L), "dec-1", maxRows, maxBytes, 5,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(120));
    }

    private ConnectionContext ctx(String cmd) {
        return new ConnectionContext(profile(), SecretValue.of(PASS), cmd);
    }

    private static final class Sink implements RowCallback {
        int headers;
        final List<List<RowCell>> rows = new ArrayList<>();
        boolean completed;
        Throwable err;

        @Override public void onHeader(RowHeader h) { headers++; }
        @Override public boolean onRow(List<RowCell> cells) { rows.add(cells); return true; }
        @Override public void onComplete() { completed = true; }
        @Override public boolean onError(Throwable t) { err = t; return true; }
    }

    private String firstCell(List<List<RowCell>> rows, int rowIdx) {
        return rows.get(rowIdx).isEmpty() ? null : rows.get(rowIdx).get(0).value();
    }

    // ====================== §16 验收：安全读命令返回值 ======================

    @Test
    @DisplayName("允许：GET 返回值")
    void allowGet() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000), ctx("GET user:1"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(1, sink.rows.size());
        assertEquals("alice", firstCell(sink.rows, 0));
        assertTrue(sink.completed);
    }

    @Test
    @DisplayName("允许：MGET 返回多行")
    void allowMget() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("MGET user:1 user:2"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(2, sink.rows.size(), "MGET 两行");
    }

    @Test
    @DisplayName("允许：HGETALL 返回字段行")
    void allowHgetall() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000), ctx("HGETALL h:1"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(2, sink.rows.size(), "HGETALL 两字段");
    }

    @Test
    @DisplayName("允许：LRANGE 返回元素")
    void allowLrange() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("LRANGE l:1 0 9"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(5, sink.rows.size(), "LRANGE 5 元素");
    }

    @Test
    @DisplayName("允许：SCAN MATCH 前缀返回 key")
    void allowScan() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("SCAN 0 MATCH user:* COUNT 100"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(sink.rows.size() >= 2, "SCAN 返回 user:* key: " + sink.rows.size());
    }

    @Test
    @DisplayName("允许：TYPE/TTL/EXISTS")
    void allowCommonRead() {
        for (String cmd : new String[]{"TYPE user:1", "TTL user:1", "EXISTS user:1"}) {
            Sink sink = new Sink();
            ExecutionResultMeta m = executor.execute(plan(100, 10_000_000), ctx(cmd), sink);
            assertEquals(ExecutionStatus.SUCCEEDED, m.status(), cmd);
        }
    }

    // ====================== §16 验收：强制拒绝语料零写入 ======================

    @Test
    @DisplayName("拒绝：KEYS 被拒绝")
    void rejectKeys() {
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000), ctx("KEYS user:*"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
    }

    @Test
    @DisplayName("拒绝：EVAL 被拒绝（无脚本执行）")
    void rejectEval() {
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("EVAL \"return 1\" 0"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
    }

    @Test
    @DisplayName("拒绝：FLUSHDB 被拒绝（零写入，数据仍在）")
    void rejectFlushdb() {
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("FLUSHDB"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        // 验证数据未被清空
        try (RedisClient client = RedisClient.create(uri());
             StatefulRedisConnection<String, String> c = client.connect(StringCodec.UTF8)) {
            assertTrue(c.sync().exists("user:1") > 0, "FLUSHDB 被拒绝，user:1 仍在");
        }
    }

    @Test
    @DisplayName("拒绝：SET 写命令被拒绝且零写入（user:999 未写入）")
    void rejectSetNoWrite() {
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("SET user:999 injected"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status(), "SET 必须被拒绝");
        // 验证零写入
        try (RedisClient client = RedisClient.create(uri());
             StatefulRedisConnection<String, String> c = client.connect(StringCodec.UTF8)) {
            assertNull(c.sync().get("user:999"), "SET 被拒绝，user:999 未写入（零写入）");
        }
    }

    @Test
    @DisplayName("拒绝：DEL 被拒绝且零写入（user:1 未删除）")
    void rejectDelNoWrite() {
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("DEL user:1"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        try (RedisClient client = RedisClient.create(uri());
             StatefulRedisConnection<String, String> c = client.connect(StringCodec.UTF8)) {
            assertTrue(c.sync().exists("user:1") > 0, "DEL 被拒绝，user:1 未删除（零写入）");
        }
    }

    @Test
    @DisplayName("拒绝：CONFIG 被拒绝")
    void rejectConfig() {
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("CONFIG GET maxmemory"), new Sink());
        assertEquals(ExecutionStatus.REJECTED, m.status());
    }

    // ====================== §16 验收：结果超限可截断 ======================

    @Test
    @DisplayName("截断：MGET maxRows=1 时返回 1 行并标记 truncated")
    void truncateMgetMaxRows() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(1, 10_000_000),
            ctx("MGET user:1 user:2"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "超 maxRows 必须截断");
        assertEquals(1, sink.rows.size());
        assertEquals(1, m.rowCount());
    }

    @Test
    @DisplayName("截断：maxBytes 小时截断")
    void truncateMaxBytes() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 1),
            ctx("GET user:1"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "超 maxBytes 必须截断");
    }

    // ====================== §16 验收：SCAN COUNT 收束 ======================

    @Test
    @DisplayName("SCAN COUNT 收束：大 COUNT 值被收束到上限，返回 key 数有限")
    void scanCountCapped() {
        Sink sink = new Sink();
        ExecutionResultMeta m = executor.execute(plan(100, 10_000_000),
            ctx("SCAN 0 MATCH user:* COUNT 999999"), sink);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        // user:1/user:2 共 2 个匹配，SCAN 返回 ≤ COUNT_CAP
        assertTrue(sink.rows.size() <= RedisQueryParser.SCAN_COUNT_CAP,
            "SCAN 返回数受 COUNT 上限约束: " + sink.rows.size());
    }
}
