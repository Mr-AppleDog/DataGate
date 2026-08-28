package org.dromara.db.connector.postgresql;

import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.error.DbServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgresqlQueryParser 单元测试（QRY-101 / docs/06 §5、§7.3、§15 安全语料）。
 *
 * <p>纯 AST 测试，不连接数据库。覆盖：允许语句、EXPLAIN/EXPLAIN ANALYZE、多语句、
 * 强制拒绝语料（COPY/SELECT INTO/CREATE TABLE AS/REFRESH/锁语句/副作用函数/DML/DDL/管理动作/DO）、
 * 失败关闭。安全语料取自 docs/06 §7.3 强制拒绝项与 §15 安全测试语料。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("PostgresqlQueryParser 安全语料测试 (QRY-101)")
class PostgresqlQueryParserTest {

    private final PostgresqlQueryParser parser = new PostgresqlQueryParser();

    /** 解析单条语句（多语句时取第一条）。 */
    private ParsedStatement one(String sql) {
        List<ParsedStatement> list = parser.parse(sql);
        assertEquals(1, list.size(), "预期单条语句: " + sql);
        return list.get(0);
    }

    // ====================== 允许语句：readonly=true / QUERY ======================

    @Test
    @DisplayName("允许：单表 SELECT -> readonly=true, QUERY, 资源完整")
    void allowSingleTableSelect() {
        ParsedStatement s = one("SELECT * FROM users");
        assertEquals("SELECT", s.statementType());
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly(), "纯只读 SELECT 必须 readonly=true");
        assertTrue(s.resourcePaths().contains("/table/users"), "资源路径含 /table/users: " + s.resourcePaths());
        assertNormalizedAndFingerprint(s);
    }

    @Test
    @DisplayName("允许：schema 限定 SELECT -> /schema/public/table/users")
    void allowSchemaQualifiedSelect() {
        ParsedStatement s = one("SELECT * FROM public.users");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/schema/public/table/users"),
            "schema 限定路径: " + s.resourcePaths());
    }

    @Test
    @DisplayName("允许：db+schema 限定 SELECT -> /db/mydb/schema/public/table/users")
    void allowDbSchemaQualifiedSelect() {
        ParsedStatement s = one("SELECT * FROM mydb.public.users");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/db/mydb/schema/public/table/users"),
            "db+schema 限定路径: " + s.resourcePaths());
    }

    @Test
    @DisplayName("允许：JOIN SELECT -> 两表资源均提取")
    void allowJoinSelect() {
        ParsedStatement s = one("SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.uid");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        List<String> paths = s.resourcePaths();
        assertTrue(paths.contains("/table/users"), "含 users: " + paths);
        assertTrue(paths.contains("/table/orders"), "含 orders: " + paths);
        assertTrue(paths.stream().anyMatch(p -> p.endsWith("/col/name")), "含列 name: " + paths);
    }

    @Test
    @DisplayName("允许：CTE SELECT -> CTE 别名不作为资源，底层表提取")
    void allowCteSelect() {
        ParsedStatement s = one("WITH cte AS (SELECT * FROM a) SELECT * FROM cte JOIN b ON cte.x = b.x");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        List<String> paths = s.resourcePaths();
        assertTrue(paths.contains("/table/a"), "CTE 体表 a: " + paths);
        assertTrue(paths.contains("/table/b"), "JOIN 表 b: " + paths);
        assertFalse(paths.contains("/table/cte"), "CTE 别名不得作为真实表资源");
    }

    @Test
    @DisplayName("允许：子查询 SELECT -> 子查询底层表提取")
    void allowSubquerySelect() {
        ParsedStatement s = one("SELECT * FROM (SELECT * FROM t2) sub");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/table/t2"), "子查询底层表 t2");
    }

    @Test
    @DisplayName("允许：UNION SELECT -> 全部分支表提取")
    void allowUnionSelect() {
        ParsedStatement s = one("SELECT * FROM t1 UNION SELECT * FROM t2 UNION SELECT * FROM t3");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        List<String> paths = s.resourcePaths();
        assertTrue(paths.contains("/table/t1") && paths.contains("/table/t2") && paths.contains("/table/t3"),
            "UNION 三分支表: " + paths);
    }

    // ====================== EXPLAIN ======================

    @Test
    @DisplayName("EXPLAIN SELECT -> EXPLAIN, readonly=true, 引用资源提取（不执行原语句）")
    void explainSelect() {
        ParsedStatement s = one("EXPLAIN SELECT * FROM users");
        assertEquals("EXPLAIN", s.statementType());
        assertEquals(DbAction.EXPLAIN, s.requiredAction());
        assertTrue(s.readonly(), "EXPLAIN 不执行原语句，readonly=true");
        assertTrue(s.resourcePaths().contains("/table/users"));
    }

    @Test
    @DisplayName("EXPLAIN ANALYZE -> 真实执行语句，readonly=false（普通用户经网关只读门禁拒绝）")
    void explainAnalyzeRejected() {
        ParsedStatement s;
        try {
            s = one("EXPLAIN ANALYZE SELECT * FROM users");
        } catch (DbServiceException e) {
            // 若 Druid 无法解析 EXPLAIN ANALYZE，失败关闭等价于拒绝
            assertEquals("QUERY_PARSE_FAILED", e.getErrorCode().name());
            return;
        }
        assertFalse(s.readonly(), "EXPLAIN ANALYZE 执行原语句，必须 readonly=false");
        assertEquals(DbAction.CHANGE_DML, s.requiredAction(),
            "EXPLAIN ANALYZE 动作必须被网关拒绝: " + s.requiredAction());
    }

    // ====================== 多语句 ======================

    @Test
    @DisplayName("多语句拆分：两条 SELECT 拆分为两条")
    void multiStatementSplit() {
        List<ParsedStatement> list = parser.parse("SELECT * FROM t1; SELECT * FROM t2");
        assertEquals(2, list.size(), "两条语句");
        assertTrue(list.get(0).readonly());
        assertTrue(list.get(1).readonly());
        assertTrue(list.get(0).resourcePaths().contains("/table/t1"));
        assertTrue(list.get(1).resourcePaths().contains("/table/t2"));
    }

    @Test
    @DisplayName("批次超 20 条上限 -> 失败关闭")
    void batchExceedsCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 21; i++) {
            if (i > 1) {
                sb.append("; ");
            }
            sb.append("SELECT ").append(i);
        }
        DbServiceException ex = assertThrows(DbServiceException.class, () -> parser.parse(sb.toString()));
        assertEquals("QUERY_PARSE_FAILED", ex.getErrorCode().name());
    }

    // ====================== 强制拒绝语料（§7.3）======================

    static Stream<Arguments> forcedRejectCorpus() {
        // docs/06 §7.3：每条强制拒绝项归为 readonly=false 且 requiredAction 为变更/管理动作
        return Stream.of(
            // SELECT INTO -> CHANGE_DDL
            Arguments.of("SELECT * INTO newtbl FROM t", DbAction.CHANGE_DDL),
            // CREATE TABLE AS -> CHANGE_DDL
            Arguments.of("CREATE TABLE x AS SELECT * FROM t", DbAction.CHANGE_DDL),
            // COPY FROM -> CHANGE_DML（导入数据）
            Arguments.of("COPY t FROM '/tmp/data.csv'", DbAction.CHANGE_DML),
            // COPY TO PROGRAM/文件 -> EXPORT（若可解析；不可解析则失败关闭等价拒绝）
            Arguments.of("COPY t TO '/tmp/out.csv'", DbAction.EXPORT),
            // REFRESH MATERIALIZED VIEW -> CHANGE_DDL
            Arguments.of("REFRESH MATERIALIZED VIEW mv", DbAction.CHANGE_DDL),
            // FOR UPDATE / FOR SHARE -> CHANGE_DML
            Arguments.of("SELECT * FROM t FOR UPDATE", DbAction.CHANGE_DML),
            Arguments.of("SELECT * FROM t FOR SHARE", DbAction.CHANGE_DML),
            Arguments.of("SELECT * FROM t FOR NO KEY UPDATE", DbAction.CHANGE_DML),
            Arguments.of("SELECT * FROM t FOR KEY SHARE", DbAction.CHANGE_DML),
            // 文件/外部程序函数 -> EXPORT
            Arguments.of("SELECT pg_read_file('/etc/passwd')", DbAction.EXPORT),
            Arguments.of("SELECT lo_import('/tmp/x')", DbAction.EXPORT),
            Arguments.of("SELECT dblink('host=remote', 'SELECT 1')", DbAction.EXPORT),
            // 副作用/DoS 函数 -> CHANGE_DML
            Arguments.of("SELECT pg_sleep(10) FROM t", DbAction.CHANGE_DML),
            // DML -> CHANGE_DML
            Arguments.of("INSERT INTO t(a) VALUES(1)", DbAction.CHANGE_DML),
            Arguments.of("UPDATE t SET a = 1", DbAction.CHANGE_DML),
            Arguments.of("DELETE FROM t", DbAction.CHANGE_DML),
            Arguments.of("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.a", DbAction.CHANGE_DML),
            // DDL 家族 -> CHANGE_DDL
            Arguments.of("CREATE TABLE x(id int)", DbAction.CHANGE_DDL),
            Arguments.of("ALTER TABLE x ADD c int", DbAction.CHANGE_DDL),
            Arguments.of("DROP TABLE x", DbAction.CHANGE_DDL),
            Arguments.of("TRUNCATE TABLE x", DbAction.CHANGE_DDL),
            Arguments.of("CREATE VIEW v AS SELECT * FROM t", DbAction.CHANGE_DDL),
            Arguments.of("CREATE INDEX i ON t(c)", DbAction.CHANGE_DDL),
            // 管理动作 -> ADMIN（VACUUM/REINDEX/CLUSTER 经 Druid 解析为管理类，或不可解析则失败关闭等价拒绝）
            Arguments.of("GRANT SELECT ON t TO u", DbAction.ADMIN),
            Arguments.of("REVOKE SELECT ON t FROM u", DbAction.ADMIN),
            Arguments.of("SET ROLE foo", DbAction.ADMIN),
            Arguments.of("SET SESSION AUTHORIZATION u", DbAction.ADMIN),
            Arguments.of("VACUUM FULL t", DbAction.ADMIN),
            Arguments.of("REINDEX TABLE t", DbAction.ADMIN),
            Arguments.of("CLUSTER t", DbAction.ADMIN),
            // 代码执行类 -> CODE（P0 禁止，ADR-007 修订）
            Arguments.of("CALL p()", DbAction.CODE)
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("forcedRejectCorpus")
    @DisplayName("强制拒绝语料：readonly=false 且 requiredAction 为变更/管理动作")
    void forcedReject(String sql, DbAction expectedAction) {
        ParsedStatement s;
        try {
            s = one(sql);
        } catch (DbServiceException e) {
            // 语义择一：若 Druid 无法解析该语句（如某些方言/DO/PL-pgSQL），失败关闭等价于拒绝
            assertEquals("QUERY_PARSE_FAILED", e.getErrorCode().name(),
                "失败关闭必须返回 QUERY_PARSE_FAILED: " + sql);
            return;
        }
        assertFalse(s.readonly(), "强制拒绝项必须 readonly=false: " + sql);
        assertEquals(expectedAction, s.requiredAction(),
            "动作分类不符: " + sql + " -> " + s.requiredAction());
    }

    // ====================== 失败关闭（§5.1 红线）======================

    static Stream<String> failClosedCorpus() {
        // docs/06 §5.1/§7.3：无法解析的新方言、PL/pgSQL 块、匿名代码块 DO、客户端元命令、未知语法 -> 抛异常
        return Stream.of(
            "DO $$ BEGIN PERFORM 1; END $$",        // 匿名代码块 DO（PL/pgSQL）
            "\\dt",                                  // psql 客户端元命令
            "SHUTDOWN",                              // 不支持的语句
            "SELEC * FROM T",                        // 未知语法/拼写错误
            "LISTEN channel1",                       // LISTEN（Druid 多半无法解析 → 失败关闭）
            "NOTIFY channel1",                       // NOTIFY
            "SELECT 1; garbage"                      // 拆留非法文本
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("failClosedCorpus")
    @DisplayName("失败关闭：未知/不支持语法抛 QUERY_PARSE_FAILED")
    void failClosed(String sql) {
        DbServiceException ex = assertThrows(DbServiceException.class, () -> parser.parse(sql),
            "必须失败关闭: " + sql);
        assertEquals("QUERY_PARSE_FAILED", ex.getErrorCode().name());
    }

    // ====================== 解析器韧性（§15 注释/大小写/字符串分号混淆）======================

    @Test
    @DisplayName("韧性：注释中的分号不拆分语句")
    void commentSemicolonNoSplit() {
        ParsedStatement s = one("SELECT * FROM t /* ; comment */ WHERE a = 1");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/table/t"));
    }

    @Test
    @DisplayName("韧性：字符串字面量中的分号不拆分语句")
    void stringSemicolonNoSplit() {
        ParsedStatement s = one("SELECT 'a;b', col FROM t WHERE x = 'c;d'");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/table/t"));
    }

    @Test
    @DisplayName("韧性：真实分号拆分、字符串内分号保留")
    void realSemicolonSplitsStringSemicolonDoesNot() {
        List<ParsedStatement> list = parser.parse("SELECT * FROM t WHERE x = ';'; SELECT * FROM t2");
        assertEquals(2, list.size(), "字符串内分号不拆分，真实分号拆分");
        assertTrue(list.get(0).resourcePaths().contains("/table/t"));
        assertTrue(list.get(1).resourcePaths().contains("/table/t2"));
    }

    @Test
    @DisplayName("韧性：大小写不敏感、表名保留原样")
    void caseInsensitive() {
        ParsedStatement s = one("select * from T Where X = 1");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/table/T"), "表名保留原样: " + s.resourcePaths());
    }

    // ====================== SHOW 安全子集（§7.2）======================

    @Test
    @DisplayName("SHOW 安全参数 -> METADATA_READ 只读（不暴露账号/权限信息）")
    void showSafeSubset() {
        ParsedStatement s;
        try {
            s = one("SHOW search_path");
        } catch (DbServiceException e) {
            // 若 Druid 无法解析 PG SHOW，失败关闭等价拒绝
            assertEquals("QUERY_PARSE_FAILED", e.getErrorCode().name());
            return;
        }
        assertEquals(DbAction.METADATA_READ, s.requiredAction());
        assertTrue(s.readonly());
    }

    // ====================== 解析器版本 ======================

    @Test
    @DisplayName("parserVersion() 锁定并返回 Druid 版本")
    void parserVersionLocked() {
        String v = parser.parserVersion();
        assertNotNull(v);
        assertTrue(v.startsWith("druid-"), "解析器版本以 druid- 前缀: " + v);
        assertTrue(v.contains("postgresql"), "解析器版本含 postgresql: " + v);
    }

    // ====================== 辅助断言 ======================

    private static void assertNormalizedAndFingerprint(ParsedStatement s) {
        assertNotNull(s.normalizedStatement());
        assertFalse(s.normalizedStatement().isBlank(), "归一化语句非空");
        assertTrue(s.fingerprint().startsWith("postgresql:"), "方言指纹前缀: " + s.fingerprint());
    }
}
