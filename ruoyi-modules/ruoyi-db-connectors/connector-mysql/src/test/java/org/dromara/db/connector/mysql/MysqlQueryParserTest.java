package org.dromara.db.connector.mysql;

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
 * MysqlQueryParser 单元测试（QRY-101 / docs/06 §5、§6.3、§15 安全语料）。
 *
 * <p>纯 AST 测试，不连接数据库。覆盖：允许语句、EXPLAIN、多语句、强制拒绝语料、失败关闭。
 * 安全语料取自 docs/06 §6.3 强制拒绝项与 §15 安全测试语料。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("MysqlQueryParser 安全语料测试 (QRY-101)")
class MysqlQueryParserTest {

    private final MysqlQueryParser parser = new MysqlQueryParser();

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
        assertTrue(s.resourcePaths().contains("/table/users"), "资源路径含 /table/users");
        assertNormalizedAndFingerprint(s);
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
        // 最佳努力列：JOIN ON 与 SELECT 列表均解析
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
        assertFalse(paths.contains("/table/cte"), "CTE 别名 cte 不得作为真实表资源");
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
    @DisplayName("允许：information_schema 安全视图 SELECT（敏感子集由鉴权层隐藏）")
    void allowInformationSchemaSelect() {
        ParsedStatement s = one("SELECT * FROM information_schema.tables WHERE table_schema = 'mydb'");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/db/information_schema/table/tables"),
            "information_schema.tables 规范路径: " + s.resourcePaths());
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

    // ====================== EXPLAIN / DESCRIBE ======================

    @Test
    @DisplayName("EXPLAIN SELECT -> EXPLAIN, readonly=true, 引用资源提取")
    void explainSelect() {
        ParsedStatement s = one("EXPLAIN SELECT * FROM users");
        assertEquals("EXPLAIN", s.statementType());
        assertEquals(DbAction.EXPLAIN, s.requiredAction());
        assertTrue(s.readonly(), "EXPLAIN 不执行原语句，readonly=true");
        assertTrue(s.resourcePaths().contains("/table/users"));
    }

    @Test
    @DisplayName("DESCRIBE tbl -> METADATA_READ, readonly=true")
    void describeTable() {
        ParsedStatement s = one("DESCRIBE users");
        assertEquals("DESCRIBE", s.statementType());
        assertEquals(DbAction.METADATA_READ, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/table/users"));
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
    @DisplayName("混批（SELECT + INSERT）：INSERT 为 readonly=false CHANGE_DML，批次风险非同质")
    void mixedBatchHighestRisk() {
        // docs/06 §5.2：READ 与变更语句混批不可执行；解析器逐条如实分类，混批拒绝由编排者实施
        List<ParsedStatement> list = parser.parse("SELECT * FROM t1; INSERT INTO t2(a) VALUES(1)");
        assertEquals(2, list.size());
        ParsedStatement select = list.get(0);
        ParsedStatement insert = list.get(1);
        assertTrue(select.readonly(), "SELECT 条目 readonly=true");
        assertEquals(DbAction.QUERY, select.requiredAction());
        assertFalse(insert.readonly(), "INSERT 条目 readonly=false");
        assertEquals(DbAction.CHANGE_DML, insert.requiredAction());
        // 批次非同质：含 readonly=true 与 readonly=false → 编排者必须整体拒绝（跨 lane 集成）
        boolean hasReadonly = list.stream().anyMatch(ParsedStatement::readonly);
        boolean hasWrite = list.stream().anyMatch(p -> !p.readonly());
        assertTrue(hasReadonly && hasWrite, "混批同时含只读与变更");
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

    // ====================== 强制拒绝语料（§6.3）======================

    static Stream<Arguments> forcedRejectCorpus() {
        // docs/06 §6.3：每条强制拒绝项归为 readonly=false 且 requiredAction 为变更/管理动作
        return Stream.of(
            // INTO OUTFILE -> EXPORT（DUMPFILE 在 Druid 1.2.28 无法解析，归失败关闭）
            Arguments.of("SELECT * FROM t INTO OUTFILE '/tmp/x'", DbAction.EXPORT),
            // LOAD_FILE -> EXPORT
            Arguments.of("SELECT LOAD_FILE('/etc/passwd')", DbAction.EXPORT),
            // FOR UPDATE / FOR SHARE / LOCK IN SHARE MODE -> CHANGE_DML
            Arguments.of("SELECT * FROM t FOR UPDATE", DbAction.CHANGE_DML),
            Arguments.of("SELECT * FROM t FOR SHARE", DbAction.CHANGE_DML),
            Arguments.of("SELECT * FROM t LOCK IN SHARE MODE", DbAction.CHANGE_DML),
            // 副作用/DoS 函数 -> CHANGE_DML
            Arguments.of("SELECT SLEEP(10) FROM t", DbAction.CHANGE_DML),
            Arguments.of("SELECT BENCHMARK(1000000, MD5(1))", DbAction.CHANGE_DML),
            // DML -> CHANGE_DML
            Arguments.of("INSERT INTO t(a) VALUES(1)", DbAction.CHANGE_DML),
            Arguments.of("UPDATE t SET a = 1", DbAction.CHANGE_DML),
            Arguments.of("DELETE FROM t", DbAction.CHANGE_DML),
            // LOAD DATA -> CHANGE_DML（从文件导入数据）
            Arguments.of("LOAD DATA INFILE '/x' INTO TABLE t", DbAction.CHANGE_DML),
            // DDL 家族 -> CHANGE_DDL
            Arguments.of("CREATE TABLE x(id int)", DbAction.CHANGE_DDL),
            Arguments.of("ALTER TABLE x ADD c int", DbAction.CHANGE_DDL),
            Arguments.of("DROP TABLE x", DbAction.CHANGE_DDL),
            Arguments.of("TRUNCATE TABLE x", DbAction.CHANGE_DDL),
            Arguments.of("CREATE VIEW v AS SELECT * FROM t", DbAction.CHANGE_DDL),
            Arguments.of("DROP VIEW v", DbAction.CHANGE_DDL),
            Arguments.of("CREATE INDEX i ON t(c)", DbAction.CHANGE_DDL),
            Arguments.of("CREATE TEMPORARY TABLE tmp AS SELECT * FROM t", DbAction.CHANGE_DDL),
            // 管理动作 -> ADMIN（对普通用户默认不可授权，ADR-007 修订）
            Arguments.of("GRANT SELECT ON db.t TO 'u'@'%'", DbAction.ADMIN),
            Arguments.of("REVOKE SELECT ON db.t FROM 'u'@'%'", DbAction.ADMIN),
            Arguments.of("CREATE USER u IDENTIFIED BY 'p'", DbAction.ADMIN),
            Arguments.of("KILL 123", DbAction.ADMIN),
            Arguments.of("LOCK TABLES t WRITE", DbAction.ADMIN),
            Arguments.of("UNLOCK TABLES", DbAction.ADMIN),
            Arguments.of("FLUSH TABLES", DbAction.ADMIN),
            Arguments.of("RESET MASTER", DbAction.ADMIN),
            Arguments.of("PURGE BINARY LOGS TO 'mysql-bin.1'", DbAction.ADMIN),
            Arguments.of("SET GLOBAL max_connections = 1", DbAction.ADMIN),
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
            // 语义择一：若 Druid 无法解析该语句（如某些方言），失败关闭等价于拒绝
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
        // docs/06 §5.1：无法解析的新方言、存储过程体、匿名代码块、客户端元命令、未知语法 -> 抛异常
        return Stream.of(
            "DO SLEEP(1)",                 // 匿名代码块（DO）
            "HANDLER t OPEN",              // HANDLER 语句（Druid 不支持）
            "INSTALL PLUGIN x SONAME 'y'", // 安装插件（Druid 不支持）
            "UNINSTALL PLUGIN x",          // 卸载插件（Druid 不支持）
            "SHUTDOWN",                    // 关闭服务器（Druid 不支持）
            "SELECT * FROM t INTO DUMPFILE '/tmp/y'", // INTO DUMPFILE（Druid 1.2.28 无法解析）
            "SELEC * FROM T",              // 未知语法/拼写错误
            "BEGIN DECLARE x INT; END;",   // 存储过程体/匿名块（解析为 SQLBlockStatement，归类失败关闭）
            "SELECT 1; garbage"            // 残留非法文本
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
    @DisplayName("韧性：行尾注释、多行字符串不破坏解析")
    void trailingCommentAndMultilineString() {
        assertTrue(parser.parse("SELECT * FROM t -- ; eol").get(0).readonly());
        assertTrue(parser.parse("SELECT * FROM t WHERE x = 'line1\nline2;'").get(0).readonly());
    }

    @Test
    @DisplayName("韧性：大小写不敏感、表名保留原样")
    void caseInsensitive() {
        ParsedStatement s = one("select * from T Where X = 1");
        assertEquals(DbAction.QUERY, s.requiredAction());
        assertTrue(s.readonly());
        assertTrue(s.resourcePaths().contains("/table/T"), "表名保留原样: " + s.resourcePaths());
    }

    // ====================== SHOW 安全子集（§6.2）======================

    @Test
    @DisplayName("SHOW 安全子集：DATABASES/TABLES/COLUMNS/INDEX/CREATE TABLE/TABLE STATUS -> METADATA_READ 只读")
    void showSafeSubset() {
        assertShowSafe("SHOW DATABASES", null);
        assertShowSafe("SHOW TABLES FROM mydb", "/db/mydb");
        assertShowSafe("SHOW COLUMNS FROM t", "/table/t");
        assertShowSafe("SHOW INDEX FROM t", "/table/t");
        assertShowSafe("SHOW CREATE TABLE t", "/table/t");
        assertShowSafe("SHOW TABLE STATUS FROM mydb", "/db/mydb");
    }

    @Test
    @DisplayName("SHOW 不安全子集：PROCESSLIST/GRANTS/VARIABLES/MASTER STATUS -> readonly=false")
    void showUnsafeSubset() {
        assertShowUnsafe("SHOW PROCESSLIST");
        assertShowUnsafe("SHOW GRANTS");
        assertShowUnsafe("SHOW VARIABLES");
        assertShowUnsafe("SHOW MASTER STATUS");
    }

    private void assertShowSafe(String sql, String expectedPath) {
        ParsedStatement s = one(sql);
        assertEquals(DbAction.METADATA_READ, s.requiredAction(), "安全 SHOW 动作: " + sql);
        assertTrue(s.readonly(), "安全 SHOW readonly=true: " + sql);
        if (expectedPath != null) {
            assertTrue(s.resourcePaths().contains(expectedPath),
                "SHOW 引用资源 " + expectedPath + ": " + s.resourcePaths());
        }
        assertNormalizedAndFingerprint(s);
    }

    private void assertShowUnsafe(String sql) {
        ParsedStatement s = one(sql);
        assertFalse(s.readonly(), "不安全 SHOW readonly=false: " + sql);
        assertEquals(DbAction.CHANGE_DDL, s.requiredAction(), "不安全 SHOW 动作: " + sql);
    }

    // ====================== 解析器版本 ======================

    @Test
    @DisplayName("parserVersion() 锁定并返回 Druid 版本")
    void parserVersionLocked() {
        String v = parser.parserVersion();
        assertNotNull(v);
        assertTrue(v.startsWith("druid-"), "解析器版本以 druid- 前缀: " + v);
    }

    // ====================== 辅助断言 ======================

    private static void assertNormalizedAndFingerprint(ParsedStatement s) {
        assertNotNull(s.normalizedStatement());
        assertFalse(s.normalizedStatement().isBlank(), "归一化语句非空");
        assertTrue(s.fingerprint().startsWith("mysql:"), "方言指纹前缀: " + s.fingerprint());
    }
}
