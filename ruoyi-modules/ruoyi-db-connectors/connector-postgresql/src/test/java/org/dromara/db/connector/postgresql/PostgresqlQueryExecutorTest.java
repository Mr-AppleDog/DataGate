package org.dromara.db.connector.postgresql;

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
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgresqlQueryExecutor 失败关闭与纵深防御测试（QRY-201 / docs/06 §4、§7.4、§11）。
 *
 * <p>纯单元测试（不连数据库）：覆盖计划校验、上下文校验、独立再解析防御、凭据销毁、cancel 幂等等
 * 失败关闭路径。流式执行（真实连接）需集成测试，见 docs/06 §16，本切片以失败关闭断言为主。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("PostgresqlQueryExecutor 失败关闭测试 (QRY-201)")
class PostgresqlQueryExecutorTest {

    private final PostgresqlQueryParser parser = new PostgresqlQueryParser();

    // ====================== 测试夹具 ======================

    private ExecutionPlan plan(Instant expires) {
        return ExecutionPlan.of("p1", 1L, 100L, "public", "public",
            "hash", "SELECT * FROM t", "SELECT", java.util.List.of(1L), "dec-1",
            1000, 10_000_000, 30, Instant.now().minusSeconds(60), expires);
    }

    private ExecutionPlan validPlan() {
        return plan(Instant.now().plusSeconds(60));
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile("127.0.0.1", 5432, "testdb", "postgres",
            new HashMap<>(), TlsMode.DISABLE, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private ConnectionContext ctx(String statement) {
        return new ConnectionContext(profile(), SecretValue.of("secret"), statement);
    }

    /** 收集 callback。 */
    private static final class CollectingCallback implements RowCallback {
        int headers;
        int rows;
        boolean completed;
        boolean errored;

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

        @Override
        public boolean onError(Throwable t) {
            errored = true;
            return true;
        }
    }

    private CollectingCallback callback() {
        return new CollectingCallback();
    }

    // ====================== 计划校验 ======================

    @Test
    @DisplayName("plan 为 null → REJECTED (QUERY_PARSE_FAILED)")
    void nullPlanRejected() {
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(null, ctx("SELECT 1"), callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
    }

    @Test
    @DisplayName("计划过期 → REJECTED (QUERY_PLAN_EXPIRED)")
    void expiredPlanRejected() {
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(plan(Instant.now().minusSeconds(1)),
            ctx("SELECT 1"), callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PLAN_EXPIRED", m.errorCode());
    }

    // ====================== 上下文校验 ======================

    @Test
    @DisplayName("ctx 为 null → REJECTED (QUERY_ENGINE_UNAVAILABLE)")
    void nullCtxRejected() {
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), null, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_ENGINE_UNAVAILABLE", m.errorCode());
    }

    @Test
    @DisplayName("secret 已销毁 → REJECTED (QUERY_ENGINE_UNAVAILABLE)")
    void destroyedSecretRejected() {
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        SecretValue secret = SecretValue.of("secret");
        secret.destroy();
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "SELECT 1");
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_ENGINE_UNAVAILABLE", m.errorCode());
    }

    // ====================== 纵深防御：独立再解析 ======================

    @Test
    @DisplayName("纵深防御：原始语句为 DDL → REJECTED (QUERY_UNSAFE_STATEMENT)，且凭据已销毁")
    void ddlStatementRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "DROP TABLE evil");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：SELECT INTO → REJECTED (QUERY_UNSAFE_STATEMENT)")
    void selectIntoRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELECT * INTO newtbl FROM t");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：FOR UPDATE → REJECTED (QUERY_UNSAFE_STATEMENT)")
    void forUpdateRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELECT * FROM t FOR UPDATE");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：COPY FROM → REJECTED（不可解析则 QUERY_PARSE_FAILED，可解析则 QUERY_UNSAFE_STATEMENT）")
    void copyFromRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "COPY t FROM '/tmp/data.csv'");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertTrue("QUERY_PARSE_FAILED".equals(m.errorCode())
                || "QUERY_UNSAFE_STATEMENT".equals(m.errorCode()),
            "COPY 必须被拒绝（失败关闭等价拒绝）: " + m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：文件函数 pg_read_file → REJECTED (QUERY_UNSAFE_STATEMENT)")
    void fileFunctionRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELECT pg_read_file('/etc/passwd')");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：DML 语句（INSERT）→ REJECTED (QUERY_UNSAFE_STATEMENT)")
    void dmlStatementRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "INSERT INTO t(a) VALUES(1)");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：EXPLAIN ANALYZE → REJECTED (QUERY_UNSAFE_STATEMENT)")
    void explainAnalyzeRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "EXPLAIN ANALYZE SELECT * FROM t");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：多语句 → REJECTED (QUERY_LIMIT_EXCEEDED)")
    void multiStatementRejectedByReparse() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELECT 1; SELECT 2");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_LIMIT_EXCEEDED", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：不可解析语句 → REJECTED (QUERY_PARSE_FAILED)")
    void unparseableStatementRejected() {
        SecretValue secret = SecretValue.of("secret");
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELEC * FORM T");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
        assertTrue(secret.isDestroyed(), "凭据使用后必须销毁");
    }

    // ====================== 凭据销毁断言（失败路径一律销毁）======================

    @Test
    @DisplayName("凭据销毁：DDL 拒绝后 secret 已 destroy")
    void credentialDestroyedOnDdlReject() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "DROP TABLE x");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx, callback());
        assertTrue(secret.isDestroyed(), "DDL 拒绝路径必须销毁凭据");
    }

    @Test
    @DisplayName("凭据销毁：解析失败拒绝后 secret 已 destroy")
    void credentialDestroyedOnParseFail() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "GARBAGE SQL ##");
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx, callback());
        assertTrue(secret.isDestroyed(), "解析失败拒绝路径必须销毁凭据");
    }

    // ====================== cancel 幂等 ======================

    @Test
    @DisplayName("executionNo 生成且以 postgresql- 前缀；cancel 幂等不抛")
    void executionNoAndCancelIdempotent() {
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx("DROP TABLE x"), callback());
        assertTrue(m.executionNo().startsWith("postgresql-"));
        exec.cancel(m.executionNo()); // 无运行语句（被拒绝），幂等不抛
        exec.cancel(null); // null 不抛
        exec.cancel("nonexistent"); // 不存在的 executionNo 不抛
    }

    // ====================== callback 不被提前调用（拒绝路径）======================

    @Test
    @DisplayName("拒绝路径：callback 不被调用（无 onHeader/onRow/onComplete）")
    void callbackNotInvokedOnReject() {
        CollectingCallback cb = callback();
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx("DROP TABLE x"), cb);
        assertEquals(0, cb.headers, "拒绝路径不应调用 onHeader");
        assertEquals(0, cb.rows, "拒绝路径不应调用 onRow");
        assertEquals(false, cb.completed, "拒绝路径不应调用 onComplete");
    }

    // ====================== parser 可空（纵深防御可降级为信任）======================

    @Test
    @DisplayName("parser 为 null：跳过再解析（信任编排者），计划校验仍生效")
    void nullParserSkipsReparse() {
        PostgresqlQueryExecutor exec = new PostgresqlQueryExecutor(null);
        ExecutionResultMeta m = exec.execute(plan(Instant.now().minusSeconds(1)),
            ctx("SELECT 1"), callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PLAN_EXPIRED", m.errorCode());
    }
}
