package org.dromara.db.connector.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.SlowQueryProvider.SlowQueryPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis SLOWLOG 采集器真实引擎集成测试（docs/07 §4.4）。
 * 连接 VM Redis 8.2。仅 -DtestTags=integration 触发。
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("Redis SLOWLOG 采集器集成测试")
class RedisSlowQueryProviderIntegrationTest {

    private static final String HOST = System.getProperty("datagate.redis.host", "192.168.149.128");
    private static final int PORT = Integer.getInteger("datagate.redis.port", 6379);
    private static final String PASS = System.getProperty("datagate.redis.pass", "mrlu");

    @BeforeAll
    static void populateSlowlog() {
        RedisURI uri = RedisURI.builder().withHost(HOST).withPort(PORT).withPassword(PASS).build();
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            RedisCommands<String, String> sync = conn.sync();
            // 记录所有命令以填充 SLOWLOG
            sync.dispatch(CommandType.CONFIG, new StatusOutput<>(StringCodec.UTF8),
                new CommandArgs<>(StringCodec.UTF8).add("SET").add("slowlog-log-slower-than").add("0"));
            sync.set("datagate:sit:1", "v1");
            sync.get("datagate:sit:1");
            sync.set("datagate:sit:2", "v2");
        }
    }

    @AfterAll
    static void restoreSlowlog() {
        RedisURI uri = RedisURI.builder().withHost(HOST).withPort(PORT).withPassword(PASS).build();
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            conn.sync().dispatch(CommandType.CONFIG, new StatusOutput<>(StringCodec.UTF8),
                new CommandArgs<>(StringCodec.UTF8).add("SET").add("slowlog-log-slower-than").add("10000"));
        }
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile(HOST, PORT, "0", "", Map.of(), TlsMode.DISABLE,
            Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("首次轮询返回 SLOWLOG 条目（命令模板，不存 value）")
    void firstPullReturnsEntries() {
        RedisSlowQueryProvider provider = new RedisSlowQueryProvider();
        SlowQueryPage page = provider.pull(profile(), SecretValue.of(PASS), null, 100);
        assertFalse(page.records().isEmpty(), "首次轮询应返回 SLOWLOG 条目");
        SlowQueryRecord r = page.records().get(0);
        assertEquals("REDIS", r.engineType());
        assertEquals("COMPLETE", r.ingestQuality());
        assertFalse(r.normalizedStatement().isBlank());
        assertTrue(r.normalizedStatement().contains("["), "命令模板应含 argc 标记");
        assertFalse(r.sanitizedSample().contains("datagate:sit"), "脱敏样例不得含 key 明文");
    }

    @Test
    @DisplayName("游标往返：第二次轮询产出 id 递增的新条目")
    void cursorRoundTrip() {
        RedisSlowQueryProvider provider = new RedisSlowQueryProvider();
        SlowQueryPage page1 = provider.pull(profile(), SecretValue.of(PASS), null, 100);
        // 再产生新命令
        RedisURI uri = RedisURI.builder().withHost(HOST).withPort(PORT).withPassword(PASS).build();
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            conn.sync().set("datagate:sit:3", "v3");
        }
        SlowQueryPage page2 = provider.pull(profile(), SecretValue.of(PASS), page1.nextCursor(), 100);
        assertFalse(page2.records().isEmpty(), "差值轮询应返回 id 递增的新条目");
    }
}
