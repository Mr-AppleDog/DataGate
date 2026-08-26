package org.dromara.db.connector.mysql;

import com.alibaba.druid.VERSION;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLCallStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLGrantStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLRevokeStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLShowStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlAlterUserStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlBinlogStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateUserStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlExplainStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlExecuteStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlFlushStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlKillStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlLoadDataInFileStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlLoadXmlStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlLockTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUnlockTablesStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.visitor.ParameterizedOutputVisitorUtils;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.spi.QueryParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MySQL 方言查询解析器（QRY-101 / docs/06 §5、§6.3）。
 *
 * <p>基于 Alibaba Druid SQL Parser 的 MySQL 方言 Parser/AST/Visitor 实现。
 * 仅使用 Druid 的解析组件，不使用 Druid 连接池，WallFilter 不作为最终授权依据
 * （AGENTS.md §6 编码铁律、docs/06 §5.1）。</p>
 *
 * <p><b>失败关闭（红线）</b>：无法解析的新方言、存储过程体、匿名代码块、客户端元命令、
 * 未知语法一律抛 {@link DbServiceException}(QUERY_PARSE_FAILED)，绝不交给数据库试运行。
 * 解析器版本经 {@link #parserVersion()} 锁定返回，作为语料回归基线（docs/06 §5.1）。</p>
 *
 * <p><b>动作分类</b>（docs/06 §5.2、§6.3）映射到冻结的 {@link DbAction}：
 * READ→QUERY、EXPLAIN→EXPLAIN、EXPORT→EXPORT、DML→CHANGE_DML、DDL→CHANGE_DDL。
 * 由于 {@link DbAction} 暂无 ADMIN/CODE 枚举，所有强制拒绝的管理/代码类语句
 * （GRANT/REVOKE/KILL/SET/CALL/USER/PURGE 等）归为 readonly=false 且 requiredAction=CHANGE_DDL
 * （平台最高风险变更动作，普通用户不可执行）；此为待集成者决策的契约缺口，见交付报告。</p>
 *
 * @author DataGate
 */
public class MysqlQueryParser implements QueryParser {

    /** P0 批次上限（docs/06 §4：批次 P0 上限 20 条）。 */
    private static final int MAX_BATCH = 20;

    /** 解析器版本（语料回归基线，docs/06 §5.1）。 */
    private static final String PARSER_VERSION = "druid-" + VERSION.getVersionNumber() + "/mysql";

    /**
     * 已知无副作用的 MySQL 内建函数白名单（QRY-102）。
     * 不在此名单且不在 {@link #FORBIDDEN_FUNCTIONS} 的函数调用视为「无法确认副作用」
     * （可能是 UDF），按 docs/06 §6.3 失败关闭：语句转为 readonly=false。
     * 白名单随解析器版本锁定，可经集成者扩充。
     */
    private static final Set<String> SAFE_FUNCTIONS = Set.of(
        // 字符串
        "UPPER", "LOWER", "UCASE", "LCASE", "CONCAT", "CONCAT_WS", "SUBSTRING", "SUBSTR", "MID",
        "LEFT", "RIGHT", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "OCTET_LENGTH", "BIT_LENGTH",
        "TRIM", "LTRIM", "RTRIM", "REPLACE", "LPAD", "RPAD", "REPEAT", "REVERSE",
        "ASCII", "ORD", "CHAR", "CHARSET", "COLLATION", "HEX", "UNHEX", "SPACE", "FORMAT",
        "LOCATE", "POSITION", "INSTR", "FIELD", "ELT", "STRCMP", "SOUNDEX", "QUOTE",
        "MAKE_SET", "EXPORT_SET", "SUBSTRING_INDEX", "REGEXP_INSTR", "REGEXP_REPLACE",
        "REGEXP_LIKE", "REGEXP_SUBSTR", "WEIGHT_STRING", "INSERT",
        // 数学
        "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "SIGN", "MOD", "POW", "POWER", "SQRT", "EXP",
        "LN", "LOG", "LOG2", "LOG10", "PI", "RAND", "TRUNCATE", "DEGREES", "RADIANS",
        "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN", "ATAN2", "COT", "CRC32", "CONV", "E",
        "GREATEST", "LEAST",
        // 日期时间
        "NOW", "CURDATE", "CURTIME", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
        "LOCALTIME", "LOCALTIMESTAMP", "SYSDATE", "UNIX_TIMESTAMP", "FROM_UNIXTIME", "UTC_DATE",
        "UTC_TIME", "UTC_TIMESTAMP", "DATE_FORMAT", "TIME_FORMAT", "DATE_ADD", "DATE_SUB",
        "ADDDATE", "SUBDATE", "ADDTIME", "SUBTIME", "DATEDIFF", "TIMEDIFF", "TIMESTAMPADD",
        "TIMESTAMPDIFF", "YEAR", "MONTH", "MONTHNAME", "DAY", "DAYNAME", "DAYOFMONTH",
        "DAYOFWEEK", "DAYOFYEAR", "HOUR", "MINUTE", "SECOND", "MICROSECOND", "QUARTER", "WEEK",
        "WEEKDAY", "WEEKOFYEAR", "YEARWEEK", "LAST_DAY", "MAKEDATE", "MAKETIME", "PERIOD_ADD",
        "PERIOD_DIFF", "TIME_TO_SEC", "SEC_TO_TIME", "STR_TO_DATE", "GET_FORMAT", "EXTRACT",
        "TO_DAYS", "TO_SECONDS",
        // 聚合 / 窗口
        "COUNT", "SUM", "AVG", "STD", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VAR_POP",
        "VAR_SAMP", "VARIANCE", "GROUP_CONCAT", "BIT_AND", "BIT_OR", "BIT_XOR", "JSON_OBJECTAGG",
        "JSON_ARRAYAGG", "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE", "LEAD", "LAG", "FIRST_VALUE",
        "LAST_VALUE", "NTH_VALUE", "CUME_DIST", "PERCENT_RANK", "PERCENTILE_CONT", "PERCENTILE_DISC",
        "MIN", "MAX",
        // JSON
        "JSON_VALID", "JSON_TYPE", "JSON_EXTRACT", "JSON_UNQUOTE", "JSON_CONTAINS",
        "JSON_CONTAINS_PATH", "JSON_KEYS", "JSON_LENGTH", "JSON_DEPTH", "JSON_INSERT",
        "JSON_ARRAY", "JSON_OBJECT", "JSON_MERGE", "JSON_MERGE_PATCH", "JSON_MERGE_PRESERVE",
        "JSON_REMOVE", "JSON_REPLACE", "JSON_SET", "JSON_SEARCH", "JSON_QUOTE", "JSON_TABLE",
        "JSON_SCHEMA_VALID", "JSON_SCHEMA_VALIDATION_REPORT", "JSON_PRETTY", "JSON_STORAGE_FREE",
        "JSON_STORAGE_SIZE", "JSON_VALUE",
        // 类型转换 / 空值 / 条件
        "CAST", "CONVERT", "INET_ATON", "INET_NTOA", "INET6_ATON", "INET6_NTOA", "ISNULL",
        "ISNOTNULL", "IFNULL", "NULLIF", "IF", "COALESCE", "INTERVAL", "VALUES",
        // 信息（只读，不暴露账号敏感信息）
        "VERSION", "DATABASE", "SCHEMA", "UUID", "UUID_SHORT", "UUID_TO_BIN", "BIN_TO_UUID",
        "IS_IPV4", "IS_IPV6", "IS_IPV4_COMPAT", "IS_IPV4_MAPPED", "STATEMENT_DIGEST",
        // 加密摘要（只读、确定性输出）
        "MD5", "SHA1", "SHA2", "SHA", "SM3", "AES_DECRYPT", "AES_ENCRYPT"
    );

    /**
     * 已知有副作用 / DoS / 文件访问的 MySQL 内建函数（docs/06 §6.3）。
     * LOAD_FILE 读取服务器文件 → EXPORT；其余 → CHANGE_DML（潜在写入/副作用）。
     */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
        "LOAD_FILE", "SLEEP", "BENCHMARK", "GET_LOCK", "RELEASE_LOCK", "RELEASE_ALL_LOCKS",
        "IS_FREE_LOCK", "IS_USED_LOCK", "MASTER_POS_WAIT", "SOURCE_POS_WAIT",
        "WAIT_FOR_EXECUTED_GTID_SET", "WAIT_UNTIL_SQL_THREAD_AFTER_GTID_SET",
        "NAME_CONST", "GTID_SUBTRACT", "GTID_SUBSET"
    );

    @Override
    public List<ParsedStatement> parse(String statement) {
        if (statement == null || statement.isBlank()) {
            throw fail("空语句", null);
        }
        List<SQLStatement> stmts = new ArrayList<>();
        MySqlStatementParser parser = new MySqlStatementParser(statement);
        try {
            parser.parseStatementList(stmts, MAX_BATCH);
        } catch (RuntimeException e) {
            // 解析阶段异常：未知方言/存储过程体/匿名块/客户端元命令 → 失败关闭
            throw fail("解析失败", e);
        }
        if (stmts.isEmpty()) {
            throw fail("无可解析语句", null);
        }
        // 未消费全部输入：要么语法残留/未知方言，要么批次超出平台硬上限 → 失败关闭
        if (!parser.getLexer().isEOF()) {
            throw fail("未完整解析或批次超出上限(" + MAX_BATCH + ")", null);
        }
        List<ParsedStatement> result = new ArrayList<>(stmts.size());
        for (SQLStatement s : stmts) {
            result.add(parseSingle(s));
        }
        return result;
    }

    @Override
    public String parserVersion() {
        return PARSER_VERSION;
    }

    // ====================== 单语句分类 ======================

    private ParsedStatement parseSingle(SQLStatement stmt) {
        // EXPLAIN / DESCRIBE 单独处理（不执行原语句，只读）
        if (stmt instanceof MySqlExplainStatement expl) {
            return parseExplain(expl);
        }

        Classification base = classify(stmt);

        // 运行 AST Visitor：提取资源 + 检测 SELECT 上下文中的强制拒绝信号。
        // 两遍遍历：第一遍构建别名/CTE 表，第二遍用完整别名表解析 SELECT 列表中的列引用
        // （Druid accept0 先访问 SELECT 列表再访问 FROM，单遍无法解析列别名）。
        ResourceCollector rc = new ResourceCollector();
        stmt.accept(rc);
        stmt.accept(rc);
        // LOAD DATA / LOAD XML 的目标表不走 SQLExprTableSource，单独提取
        if (stmt instanceof MySqlLoadDataInFileStatement load) {
            rc.addTableName(load.getTableName());
        } else if (stmt instanceof MySqlLoadXmlStatement load) {
            rc.addTableName(load.getTableName());
        }

        DbAction action = base.action;
        boolean readonly = base.readonly;
        // SELECT 系（readonly 上下文）出现强制拒绝信号 → 升级为最高风险（docs/06 §6.3）
        if (readonly) {
            if (rc.intoOutfile) {
                action = DbAction.EXPORT;
                readonly = false;
            } else if (rc.fileFunction) {
                action = DbAction.EXPORT;
                readonly = false;
            } else if (rc.forUpdate || rc.lockInShareMode) {
                action = DbAction.CHANGE_DML;
                readonly = false;
            } else if (rc.forbiddenFunction) {
                action = DbAction.CHANGE_DML;
                readonly = false;
            }
        }

        List<String> paths = rc.buildResourcePaths();
        String normalized = normalize(stmt);
        String fingerprint = "mysql:" + sha256(normalized);
        return new ParsedStatement(base.statementType, paths, normalized, fingerprint, action, readonly);
    }

    private ParsedStatement parseExplain(MySqlExplainStatement expl) {
        // DESCRIBE tbl：展示列元数据（docs/06 §6.2）
        if (expl.isDescribe()) {
            ResourceCollector rc = new ResourceCollector();
            if (expl.getTableName() != null) {
                rc.addTableName(expl.getTableName());
            }
            String normalized = normalize(expl);
            return new ParsedStatement("DESCRIBE", rc.buildResourcePaths(), normalized,
                "mysql:" + sha256(normalized), DbAction.METADATA_READ, true);
        }
        // EXPLAIN <stmt>：不执行原语句，仍需对所引用资源做 QUERY 鉴权（docs/06 §6.2）
        SQLStatement explained = expl.getStatement();
        ResourceCollector rc = new ResourceCollector();
        if (explained != null) {
            explained.accept(rc);
            explained.accept(rc);
        }
        String normalized = normalize(expl);
        return new ParsedStatement("EXPLAIN", rc.buildResourcePaths(), normalized,
            "mysql:" + sha256(normalized), DbAction.EXPLAIN, true);
    }

    /**
     * 按 AST 节点类型给出基础分类（docs/06 §5.2）。未知类型失败关闭。
     */
    private Classification classify(SQLStatement stmt) {
        if (stmt instanceof SQLSelectStatement) {
            return new Classification("SELECT", DbAction.QUERY, true);
        }
        if (stmt instanceof SQLInsertStatement || stmt instanceof MySqlInsertStatement) {
            return new Classification("INSERT", DbAction.CHANGE_DML, false);
        }
        if (stmt instanceof SQLUpdateStatement || stmt instanceof MySqlUpdateStatement) {
            return new Classification("UPDATE", DbAction.CHANGE_DML, false);
        }
        if (stmt instanceof SQLDeleteStatement || stmt instanceof MySqlDeleteStatement) {
            return new Classification("DELETE", DbAction.CHANGE_DML, false);
        }
        if (stmt instanceof SQLMergeStatement) {
            return new Classification("MERGE", DbAction.CHANGE_DML, false);
        }
        // LOAD DATA / LOAD XML：从文件导入数据 → DML 写入
        if (stmt instanceof MySqlLoadDataInFileStatement || stmt instanceof MySqlLoadXmlStatement) {
            return new Classification("LOAD", DbAction.CHANGE_DML, false);
        }
        // CALL / EXECUTE(prepared)：CODE 类，P0 禁止
        if (stmt instanceof SQLCallStatement || stmt instanceof MySqlExecuteStatement) {
            return new Classification("CALL", DbAction.CHANGE_DDL, false);
        }
        // SET：客户端不得修改会话关键参数（docs/06 §6.4），统一归为管理动作
        if (stmt instanceof SQLSetStatement) {
            return new Classification("SET", DbAction.CHANGE_DDL, false);
        }
        // SHOW：安全子集（§6.2）的精细过滤与结果按授权回放属元数据 lane，本切片保守失败关闭
        if (stmt instanceof SQLShowStatement) {
            return new Classification("SHOW", DbAction.CHANGE_DDL, false);
        }
        // 管理动作（§6.3 强制拒绝项）：USER / GRANT / REVOKE / KILL / LOCK / UNLOCK / FLUSH / BINLOG
        if (stmt instanceof MySqlCreateUserStatement || stmt instanceof MySqlAlterUserStatement
            || stmt instanceof SQLGrantStatement || stmt instanceof SQLRevokeStatement
            || stmt instanceof MySqlKillStatement || stmt instanceof MySqlLockTableStatement
            || stmt instanceof MySqlUnlockTablesStatement || stmt instanceof MySqlFlushStatement
            || stmt instanceof MySqlBinlogStatement) {
            return new Classification(adminVerb(stmt), DbAction.CHANGE_DDL, false);
        }
        // RESET / PURGE：Druid 解析为通用 SQLResetStatement / SQLPurgeLogsStatement，归管理动作
        String cn = stmt.getClass().getSimpleName();
        if (cn.contains("Reset")) {
            return new Classification("RESET", DbAction.CHANGE_DDL, false);
        }
        if (cn.contains("Purge")) {
            return new Classification("PURGE", DbAction.CHANGE_DDL, false);
        }
        // DDL 家族：CREATE / ALTER / DROP / TRUNCATE / RENAME（含临时表创建、写入型对象）
        String verb = ddlVerb(stmt);
        if (verb != null) {
            return new Classification(verb, DbAction.CHANGE_DDL, false);
        }
        // 未知语法 → 失败关闭
        throw fail("不支持的语句类型: " + stmt.getClass().getSimpleName(), null);
    }

    /** DDL 家族动词判定（按 AST 节点的 Java 类名前缀，节点的解析已由 AST 完成）。 */
    private String ddlVerb(SQLStatement stmt) {
        String cn = stmt.getClass().getSimpleName();
        if (cn.startsWith("SQLCreate") || cn.startsWith("MySqlCreate")) {
            return "CREATE";
        }
        if (cn.startsWith("SQLAlter") || cn.startsWith("MySqlAlter")) {
            return "ALTER";
        }
        if (cn.startsWith("SQLDrop") || cn.startsWith("MySqlDrop")) {
            return "DROP";
        }
        if (cn.startsWith("SQLTruncate") || cn.startsWith("MySqlTruncate")) {
            return "TRUNCATE";
        }
        if (cn.startsWith("SQLRename") || cn.startsWith("MySqlRename")) {
            return "RENAME";
        }
        return null;
    }

    private String adminVerb(SQLStatement stmt) {
        String cn = stmt.getClass().getSimpleName();
        if (cn.contains("User")) {
            return "USER";
        }
        if (cn.contains("Grant")) {
            return "GRANT";
        }
        if (cn.contains("Revoke")) {
            return "REVOKE";
        }
        if (cn.contains("Kill")) {
            return "KILL";
        }
        if (cn.contains("Unlock")) {
            return "UNLOCK";
        }
        if (cn.contains("Lock")) {
            return "LOCK";
        }
        if (cn.contains("Flush")) {
            return "FLUSH";
        }
        if (cn.contains("Reset")) {
            return "RESET";
        }
        if (cn.contains("Binlog")) {
            return "BINLOG";
        }
        return "ADMIN";
    }

    // ====================== 归一化与指纹 ======================

    /** 去常量归一化语句（Druid ParameterizedOutputVisitor，QRY-103）。 */
    private String normalize(SQLStatement stmt) {
        try {
            String p = ParameterizedOutputVisitorUtils.parameterize(stmt, com.alibaba.druid.DbType.mysql);
            if (p != null && !p.isBlank()) {
                return p;
            }
        } catch (RuntimeException ignored) {
            // 参数化失败时回退到 AST 还原（仍为合法 SQL，但不一定去常量）
        }
        try {
            return SQLUtils.toMySqlString(stmt);
        } catch (RuntimeException e) {
            throw fail("归一化失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 内置算法，理论不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static DbServiceException fail(String reason, Throwable cause) {
        // 对外不泄露底层堆栈/方言细节；原因面向用户，仅记错误码（docs/05 §3、docs/08 6.2）
        if (cause != null) {
            return new DbServiceException(DbErrorCode.QUERY_PARSE_FAILED,
                "语句解析失败(" + reason + "): " + cause.getClass().getSimpleName());
        }
        return new DbServiceException(DbErrorCode.QUERY_PARSE_FAILED, "语句解析失败(" + reason + ")");
    }

    // ====================== 基础分类结构 ======================

    private record Classification(String statementType, DbAction action, boolean readonly) {
    }

    // ====================== AST 资源提取与信号检测 ======================

    /**
     * AST 访问者：遍历整棵语法树，提取 db/table/column 规范资源路径，
     * 并在 SELECT 上下文中检测 INTO OUTFILE、FOR UPDATE、LOCK IN SHARE MODE、
     * 副作用函数等强制拒绝信号（docs/06 §6.3）。
     *
     * <p>资源路径风格与 {@link org.dromara.db.core.domain.ResourceNode#canonicalPath()} 一致：
     * {@code /db/<db>/table/<t>}（MySQL 无独立 schema 层，database 即 schema），
     * 显式限定列追加 {@code /col/<c>}。未限定库名的表引用以 {@code /table/<t>} 表示，
     * 由编排者用连接默认库补全（跨 lane 集成决策，待集成者确认）。</p>
     */
    private static final class ResourceCollector extends MySqlASTVisitorAdapter {

        /** 规范化表/视图资源路径（保持插入顺序）。 */
        private final LinkedHashSet<String> tables = new LinkedHashSet<>();
        /** 显式限定列路径（owner 可解析为已捕获表源的别名/表名时）。 */
        private final LinkedHashSet<String> columns = new LinkedHashSet<>();
        /** 别名/表名 → 表路径（key 小写以匹配 MySQL 大小写不敏感解析）。 */
        private final HashMap<String, String> aliasToPath = new HashMap<>();
        /** CTE 别名（小写）：不作为真实表资源，但其体仍递归提取底层表。 */
        private final HashSet<String> cteAliases = new HashSet<>();

        private boolean intoOutfile;
        private boolean fileFunction;
        private boolean forUpdate;
        private boolean lockInShareMode;
        private boolean forbiddenFunction;

        @Override
        public boolean visit(SQLWithSubqueryClause x) {
            for (SQLWithSubqueryClause.Entry entry : x.getEntries()) {
                String alias = entry.getAlias();
                if (alias != null && !alias.isBlank()) {
                    cteAliases.add(match(alias));
                }
            }
            return true; // 递归进 CTE 体，提取底层真实表
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            SQLName name = x.getName();
            if (name == null) {
                // 非 SQLName（如 INTO OUTFILE 的文件字面量）→ 不作为表资源
                return true;
            }
            String table = x.getTableName();
            if (table == null || table.isBlank()) {
                return true;
            }
            String matchTable = match(table);
            if (cteAliases.contains(matchTable)) {
                // CTE 引用，非真实表；不产出资源路径
                String alias = x.getAlias();
                if (alias != null && !alias.isBlank()) {
                    aliasToPath.put(match(alias), null);
                }
                return true;
            }
            String db = x.getSchema();
            if (db == null || db.isBlank()) {
                db = x.getCatalog();
            }
            String tablePath = (db == null || db.isBlank())
                ? "/table/" + path(table)
                : "/db/" + path(db) + "/table/" + path(table);
            tables.add(tablePath);
            aliasToPath.put(matchTable, tablePath);
            String alias = x.getAlias();
            if (alias != null && !alias.isBlank()) {
                aliasToPath.put(match(alias), tablePath);
            }
            return true;
        }

        @Override
        public boolean visit(SQLSelectQueryBlock x) {
            if (x.getInto() != null) {
                // SELECT ... INTO OUTFILE/DUMPFILE/变量（docs/06 §6.3）
                intoOutfile = true;
            }
            if (x.isForUpdate()) {
                // SELECT ... FOR UPDATE（docs/06 §6.3）
                forUpdate = true;
            }
            if (x.isForShare()) {
                // SELECT ... FOR SHARE（MySQL 8.0，等价 LOCK IN SHARE MODE，docs/06 §6.3）
                lockInShareMode = true;
            }
            if (x instanceof MySqlSelectQueryBlock mq && mq.isLockInShareMode()) {
                // SELECT ... LOCK IN SHARE MODE（docs/06 §6.3）
                lockInShareMode = true;
            }
            return true;
        }

        @Override
        public boolean visit(MySqlSelectQueryBlock x) {
            visit((SQLSelectQueryBlock) x);
            return true;
        }

        @Override
        public boolean visit(SQLPropertyExpr x) {
            // 最佳努力列提取：owner 可解析为已捕获表源时产出列路径
            String col = x.getSimpleName();
            if (col == null || col.isBlank()) {
                return true;
            }
            String owner = x.getOwnerName();
            if (owner == null) {
                return true;
            }
            String tp = aliasToPath.get(match(owner));
            if (tp != null) {
                columns.add(tp + "/col/" + path(col));
            }
            return true;
        }

        @Override
        public boolean visit(SQLMethodInvokeExpr x) {
            String fn = x.getMethodName();
            if (fn == null || fn.isBlank()) {
                return true;
            }
            String up = fn.toUpperCase();
            if ("LOAD_FILE".equals(up)) {
                fileFunction = true;
            } else if (FORBIDDEN_FUNCTIONS.contains(up)) {
                forbiddenFunction = true;
            } else if (!SAFE_FUNCTIONS.contains(up)) {
                // 无法确认副作用的函数（可能是 UDF）→ 失败关闭（docs/06 §6.3）
                forbiddenFunction = true;
            }
            return true;
        }

        /** DESCRIBE / LOAD DATA 直接给出的表名（SQLName）补一条表资源。 */
        void addTableName(SQLName name) {
            if (name == null) {
                return;
            }
            String s = name.getSimpleName();
            if (s != null && !s.isBlank()) {
                tables.add("/table/" + path(s));
            }
        }

        List<String> buildResourcePaths() {
            List<String> all = new ArrayList<>(tables);
            all.addAll(columns);
            return all;
        }

        /** 匹配用：去除反引号/引号并小写（MySQL 标识符解析大小写不敏感）。 */
        private static String match(String id) {
            return stripQuotes(id).toLowerCase();
        }

        /** 路径用：去除反引号/引号，保留原大小写（大小写归一化由资源目录裁定）。 */
        private static String path(String id) {
            return stripQuotes(id);
        }

        private static String stripQuotes(String id) {
            if (id == null) {
                return "";
            }
            String s = id.trim();
            if (s.length() >= 2) {
                char f = s.charAt(0);
                char l = s.charAt(s.length() - 1);
                if ((f == '`' && l == '`') || (f == '"' && l == '"')) {
                    return s.substring(1, s.length() - 1);
                }
            }
            return s;
        }
    }
}
