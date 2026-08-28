package org.dromara.db.connector.mysql;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MysqlQueryExecutor 失败关闭与纵深防御测试（QRY-201 / docs/06 §4、§6.4、§11）。
 *
 * <p>纯单元测试（不连数据库）：覆盖计划校验、上下文校验、独立再解析防御、凭据销毁、cancel 幂等等
 * 失败关闭路径。流式执行（真实连接）需集成测试，见 docs/06 §16，本切片以失败关闭断言为主。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("MysqlQueryExecutor 失败关闭测试 (QRY-201)")
class MysqlQueryExecutorTest {

    private final MysqlQueryParser parser = new MysqlQueryParser();

    // ====================== 测试夹具 ======================

    private ExecutionPlan plan(Instant expires) {
        return ExecutionPlan.of("p1", 1L, 100L, "testdb", null,
            "hash", "SELECT * FROM t", "SELECT", java.util.List.of(1L), "dec-1",
            1000, 10_000_000, 30, Instant.now().minusSeconds(60), expires);
    }

    private ExecutionPlan validPlan() {
        return plan(Instant.now().plusSeconds(60));
    }

    private ConnectionProfile profile() {
        return new ConnectionProfile("127.0.0.1", 3306, "testdb", "root",
            new HashMap<>(), TlsMode.DISABLE, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private ConnectionContext ctx(String statement) {
        return new ConnectionContext(profile(), SecretValue.of("secret"), statement);
    }

    /** 收集 callback：计数 onHeader/onRow，记录是否 onComplete/onError。 */
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
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(null, ctx("SELECT 1"), callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
    }

    @Test
    @DisplayName("计划过期 → REJECTED (QUERY_PLAN_EXPIRED)")
    void expiredPlanRejected() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(plan(Instant.now().minusSeconds(1)),
            ctx("SELECT 1"), callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PLAN_EXPIRED", m.errorCode());
    }

    // ====================== 上下文校验 ======================

    @Test
    @DisplayName("ctx 为 null → REJECTED (QUERY_ENGINE_UNAVAILABLE)")
    void nullCtxRejected() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), null, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_ENGINE_UNAVAILABLE", m.errorCode());
    }

    @Test
    @DisplayName("secret 已销毁 → REJECTED (QUERY_ENGINE_UNAVAILABLE)")
    void destroyedSecretRejected() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
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
        SecretValue[] holder = new SecretValue[1];
        SecretValue secret = SecretValue.of("secret");
        holder[0] = secret;
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "DROP TABLE evil");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertNotNull(holder[0]);
        assertTrue(holder[0].isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：FOR UPDATE → REJECTED (QUERY_UNSAFE_STATEMENT)，且凭据已销毁")
    void forUpdateRejectedByReparse() {
        SecretValue[] holder = new SecretValue[1];
        SecretValue secret = SecretValue.of("secret");
        holder[0] = secret;
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELECT * FROM t FOR UPDATE");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
        assertTrue(holder[0].isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：多语句 → REJECTED (QUERY_LIMIT_EXCEEDED)，且凭据已销毁")
    void multiStatementRejectedByReparse() {
        SecretValue[] holder = new SecretValue[1];
        SecretValue secret = SecretValue.of("secret");
        holder[0] = secret;
        ConnectionContext ctx = new ConnectionContext(profile(), secret,
            "SELECT 1; SELECT 2");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_LIMIT_EXCEEDED", m.errorCode());
        assertTrue(holder[0].isDestroyed(), "凭据使用后必须销毁");
    }

    @Test
    @DisplayName("纵深防御：ADMIN 语句（GRANT）→ REJECTED (QUERY_UNSAFE_STATEMENT)")
    void adminStatementRejectedByReparse() {
        ConnectionContext ctx = new ConnectionContext(profile(), SecretValue.of("secret"),
            "GRANT SELECT ON db.t TO 'u'@'%'");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
    }

    @Test
    @DisplayName("纵深防御：CODE 语句（CALL）→ REJECTED (QUERY_UNSAFE_STATEMENT)")
    void codeStatementRejectedByReparse() {
        ConnectionContext ctx = new ConnectionContext(profile(), SecretValue.of("secret"),
            "CALL p()");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
    }

    @Test
    @DisplayName("纵深防御：DML 语句（INSERT）→ REJECTED (QUERY_UNSAFE_STATEMENT)")
    void dmlStatementRejectedByReparse() {
        ConnectionContext ctx = new ConnectionContext(profile(), SecretValue.of("secret"),
            "INSERT INTO t(a) VALUES(1)");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_UNSAFE_STATEMENT", m.errorCode());
    }

    @Test
    @DisplayName("纵深防御：不可解析语句 → REJECTED (QUERY_PARSE_FAILED)")
    void unparseableStatementRejected() {
        ConnectionContext ctx = new ConnectionContext(profile(), SecretValue.of("secret"),
            "SELEC * FORM T");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx, callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PARSE_FAILED", m.errorCode());
    }

    // ====================== 凭据销毁断言（失败路径一律销毁）======================

    @Test
    @DisplayName("凭据销毁：DDL 拒绝后 secret 已 destroy")
    void credentialDestroyedOnDdlReject() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "DROP TABLE x");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx, callback());
        assertTrue(secret.isDestroyed(), "DDL 拒绝路径必须销毁凭据");
    }

    @Test
    @DisplayName("凭据销毁：多语句拒绝后 secret 已 destroy")
    void credentialDestroyedOnMultiReject() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "SELECT 1; DROP TABLE x");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx, callback());
        assertTrue(secret.isDestroyed(), "多语句拒绝路径必须销毁凭据");
    }

    @Test
    @DisplayName("凭据销毁：解析失败拒绝后 secret 已 destroy")
    void credentialDestroyedOnParseFail() {
        SecretValue secret = SecretValue.of("pw");
        ConnectionContext ctx = new ConnectionContext(profile(), secret, "GARBAGE SQL ##");
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx, callback());
        assertTrue(secret.isDestroyed(), "解析失败拒绝路径必须销毁凭据");
    }

    // ====================== cancel 幂等 ======================

    @Test
    @DisplayName("executionNo 生成且以 mysql- 前缀；cancel 幂等不抛")
    void executionNoAndCancelIdempotent() {
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        ExecutionResultMeta m = exec.execute(validPlan(), ctx("DROP TABLE x"), callback());
        assertTrue(m.executionNo().startsWith("mysql-"));
        exec.cancel(m.executionNo()); // 无运行语句（被拒绝），幂等不抛
        exec.cancel(null); // null 不抛
        exec.cancel("nonexistent"); // 不存在的 executionNo 不抛
    }

    // ====================== callback 不被提前调用（拒绝路径无 onHeader/onRow）======================

    @Test
    @DisplayName("拒绝路径：callback 不被调用（无 onHeader/onRow/onComplete）")
    void callbackNotInvokedOnReject() {
        CollectingCallback cb = callback();
        MysqlQueryExecutor exec = new MysqlQueryExecutor(parser);
        exec.execute(validPlan(), ctx("DROP TABLE x"), cb);
        assertEquals(0, cb.headers, "拒绝路径不应调用 onHeader");
        assertEquals(0, cb.rows, "拒绝路径不应调用 onRow");
        assertEquals(false, cb.completed, "拒绝路径不应调用 onComplete");
    }

    // ====================== parser 可空（纵深防御可降级为信任）======================

    @Test
    @DisplayName("parser 为 null：跳过再解析（信任编排者），计划校验仍生效")
    void nullParserSkipsReparse() {
        // parser 为 null 时执行器不做独立再解析，但仍校验计划与上下文
        // 过期计划仍拒绝
        MysqlQueryExecutor exec = new MysqlQueryExecutor(null);
        ExecutionResultMeta m = exec.execute(plan(Instant.now().minusSeconds(1)),
            ctx("SELECT 1"), callback());
        assertEquals(ExecutionStatus.REJECTED, m.status());
        assertEquals("QUERY_PLAN_EXPIRED", m.errorCode());
    }
}
