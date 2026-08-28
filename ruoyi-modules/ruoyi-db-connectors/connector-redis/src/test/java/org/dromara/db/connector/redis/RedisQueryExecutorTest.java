package org.dromara.db.connector.redis;

import org.dromara.db.connector.redis.support.RedisCommandRunner;
import org.dromara.db.connector.redis.support.RedisLimits;
import org.dromara.db.connector.redis.support.RedisResponse;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedisQueryExecutor 失败关闭、纵深防御与流式限制测试（REDIS-201 / docs/06 §8.2、§11）。
 *
 * <p>纯单元测试（不连 Redis）：用桩 {@link RedisCommandRunner} 注入验证流式行/字节上限与截断；
 * 失败关闭路径（计划/上下文/再解析/凭据销毁/cancel 幂等）不调用桩。真实连接需集成测试（§16）。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("RedisQueryExecutor 失败关闭与流式限制测试 (REDIS-201)")
class RedisQueryExecutorTest {

    private final RedisQueryParser parser = new RedisQueryParser();

    // ====================== 测试夹具 ======================

    private ExecutionPlan plan(long maxRows, long maxBytes, Instant expires) {
        return ExecutionPlan.of("p1", 1L, 100L, "0", null,
            "hash", "GET user:1", "GET", java.util.List.of(1L), "dec-1",
            maxRows, maxBytes, 5, Instant.now().minusSeconds(60), expires);
    }

    private ExecutionPlan validPlan() {
        return plan(100, 10_000_000, Instant.now().plusSeconds(60));
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile("127.0.0.1", 6379, "0", "default",
            new HashMap<>(), TlsMode.DISABLE, Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    private ConnectionContext ctx(String cmd) {
        return new ConnectionContext(profile(), SecretValue.of("secret"), cmd);
    }

    /** 桩执行器：按 verb 返回固定 RedisResponse。 */
    private static final class StubRunner implements RedisCommandRunner {
        RedisResponse resp;
        String lastVerb;
        List<String> lastArgs;
        int scanCount;

        StubRunner(RedisResponse resp) {
            this.resp = resp;
        }

        @Override
        public RedisResponse run(org.dromara.db.core.domain.ConnectionProfile profile,
                                 org.dromara.db.core.security.SecretValue secret,
                                 String verb, List<String> args, RedisLimits limits) {
            lastVerb = verb;
            lastArgs = args;
            scanCount = limits.scanCount();
            return resp;
        }
    }

    private static final class CollectingCallback implements RowCallback {
        int headers;
        int rows;
        boolean completed;

        @Override
        public void onHeader(RowHeader header) {
            headers++;
        }

        @Override
        public boolean onRow(List<RowCell> cells) {
            rows++;
            return true;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    private static RowHeader header(String... cols) {
        List<ColumnMeta> list = new ArrayList<>();
        for (String c : cols) {
            list.add(new ColumnMeta(c, "STRING", "text"));
        }
        return new RowHeader(list);
    }

    private static List<RowCell> row(String... vals) {
        List<RowCell> cells = new ArrayList<>();
        for (String v : vals) {
            cells.add(new RowCell(v, false, null));
        }
        return cells;
    }

    /** 多行响应。 */
    private static RedisResponse multiRows(int n) {
        List<List<RowCell>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row("user:" + i));
        }
        return new RedisResponse(header("key"), rows, false);
    }

    // ====================== 计划/上下文校验 ======================

    @Test
    @DisplayName("plan 为 null → REJECTED (QUERY_PARSE_FAILED)")
    void nullPlanRejected() {
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(null, ctx("GET user:1"), new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
    }

    @Test
    @DisplayName("计划过期 → REJECTED (QUERY_PLAN_EXPIRED)")
    void expiredPlanRejected() {
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(plan(100, 10_000_000, Instant.now().minusSeconds(1)),
            ctx("GET user:1"), new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PLAN_EXPIRED", m.errorCode());
    }

    @Test
    @DisplayName("ctx 为 null → REJECTED (QUERY_ENGINE_UNAVAILABLE)")
    void nullCtxRejected() {
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(validPlan(), null, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_ENGINE_UNAVAILABLE", m.errorCode());
    }

    @Test
    @DisplayName("secret 已销毁 → REJECTED (QUERY_ENGINE_UNAVAILABLE)")
    void destroyedSecretRejected() {
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        SecretValue secret = SecretValue.of("secret");
        secret.destroy();
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "GET user:1");
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_ENGINE_UNAVAILABLE", m.errorCode());
    }

    // ====================== 纵深防御：再解析 ======================

    @Test
    @DisplayName("纵深防御：写命令 SET → REJECTED (QUERY_UNSAFE_STATEMENT)，凭据销毁")
    void writeCommandRejected() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "SET user:1 v");
        StubRunner stub = new StubRunner(null);
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
        assertEquals(null, stub.lastVerb, "拒绝路径不应调用派发器");
    }

    @Test
    @DisplayName("纵深防御：危险命令 KEYS → REJECTED (QUERY_UNSAFE_STATEMENT)")
    void forbiddenCommandRejected() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "KEYS user:*");
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed());
    }

    @Test
    @DisplayName("纵深防御：脚本命令 EVAL → REJECTED (QUERY_UNSAFE_STATEMENT)")
    void scriptCommandRejected() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "EVAL \"return 1\" 0");
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed());
    }

    @Test
    @DisplayName("纵深防御：未知命令 FOO → REJECTED (QUERY_PARSE_FAILED)")
    void unknownCommandRejected() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "FOO user:1");
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
        assertTrue(secret.isDestroyed());
    }

    @Test
    @DisplayName("纵深防御：管道多命令 → REJECTED (QUERY_PARSE_FAILED)")
    void pipeMultiRejected() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "GET user:1 | DEL user:1");
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, new CollectingCallback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
        assertTrue(secret.isDestroyed());
    }

    // ====================== 流式成功路径与上限 ======================

    @Test
    @DisplayName("成功：GET 桩返回单值 → SUCCEEDED，callback header+1 row")
    void successGet() {
        StubRunner stub = new StubRunner(new RedisResponse(header("value"),
            List.of(row("hello")), false));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        CollectingCallback cb = new CollectingCallback();
        ExecutionResultMeta m = exec.execute(validPlan(), ctx("GET user:1"), cb);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertEquals(1, cb.headers, "onHeader 调用一次");
        assertEquals(1, cb.rows, "onRow 调用一次");
        assertTrue(cb.completed, "onComplete 调用");
        assertEquals(1, m.rowCount());
        assertEquals("GET", stub.lastVerb, "派发器收到 GET");
    }

    @Test
    @DisplayName("上限：MGET 桩返回 5 行，maxRows=2 → SUCCEEDED truncated，rowCount=2")
    void rowLimitTruncates() {
        StubRunner stub = new StubRunner(multiRows(5));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        CollectingCallback cb = new CollectingCallback();
        ExecutionResultMeta m = exec.execute(plan(2, 10_000_000, Instant.now().plusSeconds(60)),
            ctx("MGET user:1 user:2"), cb);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "超 maxRows 必须截断");
        assertEquals(2, m.rowCount(), "只吐 maxRows 行");
        assertEquals(2, cb.rows);
    }

    @Test
    @DisplayName("上限：单行超大字节，maxBytes 小 → SUCCEEDED truncated")
    void byteLimitTruncates() {
        StubRunner stub = new StubRunner(multiRows(1));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        CollectingCallback cb = new CollectingCallback();
        // maxBytes=1 必然超限
        ExecutionResultMeta m = exec.execute(plan(100, 1, Instant.now().plusSeconds(60)),
            ctx("GET user:1"), cb);
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "超 maxBytes 必须截断");
    }

    @Test
    @DisplayName("上限：派发器自身截断透传到执行器 truncated")
    void runnerTruncationPropagated() {
        StubRunner stub = new StubRunner(new RedisResponse(header("key"), List.of(row("k1")), true));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx("SMEMBERS user:1"), new CollectingCallback());
        assertEquals(ExecutionStatus.SUCCEEDED, m.status());
        assertTrue(m.truncated(), "派发器截断必须透传");
    }

    @Test
    @DisplayName("SCAN 强制 COUNT：派发器收到 SCAN_COUNT_CAP 上限")
    void scanCountEnforced() {
        StubRunner stub = new StubRunner(new RedisResponse(header("key"), List.of(), false));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        exec.execute(validPlan(), ctx("SCAN 0 MATCH user:*"), new CollectingCallback());
        assertTrue(stub.scanCount > 0, "派发器收到 COUNT 上限: " + stub.scanCount);
        // args 中应注入/收束 COUNT
        int countIdx = -1;
        for (int i = 0; i + 1 < stub.lastArgs.size(); i++) {
            if ("COUNT".equalsIgnoreCase(stub.lastArgs.get(i))) {
                countIdx = i;
                break;
            }
        }
        assertTrue(countIdx >= 0, "SCAN args 注入 COUNT: " + stub.lastArgs);
        assertEquals(RedisQueryParser.SCAN_COUNT_CAP, Integer.parseInt(stub.lastArgs.get(countIdx + 1)));
    }

    @Test
    @DisplayName("SCAN 用户 COUNT 收束至上限")
    void scanCountCapped() {
        StubRunner stub = new StubRunner(new RedisResponse(header("key"), List.of(), false));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        exec.execute(validPlan(), ctx("SCAN 0 MATCH user:* COUNT 999999"), new CollectingCallback());
        int countIdx = -1;
        for (int i = 0; i + 1 < stub.lastArgs.size(); i++) {
            if ("COUNT".equalsIgnoreCase(stub.lastArgs.get(i))) {
                countIdx = i;
                break;
            }
        }
        assertTrue(countIdx >= 0);
        assertEquals(RedisQueryParser.SCAN_COUNT_CAP, Integer.parseInt(stub.lastArgs.get(countIdx + 1)),
            "用户 COUNT 收束至上限");
    }

    // ====================== 凭据销毁（所有路径）======================

    @Test
    @DisplayName("凭据销毁：成功路径后 secret 已 destroy")
    void credentialDestroyedOnSuccess() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "GET user:1");
        RedisQueryExecutor exec = new RedisQueryExecutor(parser,
            new StubRunner(new RedisResponse(header("value"), List.of(row("v")), false)));
        exec.execute(validPlan(), ctx, new CollectingCallback());
        assertTrue(secret.isDestroyed(), "成功路径必须销毁凭据");
    }

    @Test
    @DisplayName("凭据销毁：拒绝路径后 secret 已 destroy")
    void credentialDestroyedOnReject() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "SET user:1 v");
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        exec.execute(validPlan(), ctx, new CollectingCallback());
        assertTrue(secret.isDestroyed(), "拒绝路径必须销毁凭据");
    }

    // ====================== cancel 幂等 ======================

    @Test
    @DisplayName("executionNo 以 redis- 前缀；cancel 幂等不抛")
    void cancelIdempotent() {
        StubRunner stub = new StubRunner(new RedisResponse(header("value"), List.of(row("v")), false));
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, stub);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx("GET user:1"), new CollectingCallback());
        assertTrue(m.executionNo().startsWith("redis-"));
        exec.cancel(m.executionNo());
        exec.cancel(null);
        exec.cancel("nonexistent");
    }

    @Test
    @DisplayName("拒绝路径：callback 不被调用")
    void callbackNotInvokedOnReject() {
        CollectingCallback cb = new CollectingCallback();
        RedisQueryExecutor exec = new RedisQueryExecutor(parser, new StubRunner(null));
        exec.execute(validPlan(), ctx("SET user:1 v"), cb);
        assertEquals(0, cb.headers);
        assertEquals(0, cb.rows);
        assertEquals(false, cb.completed);
    }
}
