package org.dromara.db.connector.postgresql;

import com.alibaba.druid.VERSION;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLCallStatement;
import com.alibaba.druid.sql.ast.statement.SQLCopyFromStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExplainAnalyzeStatement;
import com.alibaba.druid.sql.ast.statement.SQLExplainStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLGrantStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLRefreshMaterializedViewStatement;
import com.alibaba.druid.sql.ast.statement.SQLRevokeStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLShowStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGDeleteStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGShowStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGUpdateStatement;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGSQLStatementParser;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL 方言查询解析器（QRY-101 / docs/06 §5、§7.3、§15）。
 *
 * <p>基于 Alibaba Druid SQL Parser 的 PostgreSQL 方言 Parser/AST/Visitor 实现。
 * 仅使用 Druid 的解析组件，不使用 Druid 连接池，WallFilter 不作为最终授权依据
 * （AGENTS.md §6 编码铁律、docs/06 §5.1）。</p>
 *
 * <p><b>失败关闭（红线）</b>：无法解析的新方言、存储过程体（PL/pgSQL）、匿名代码块 DO、
 * 客户端元命令（\d 等）、未知语法一律抛 {@link DbServiceException}(QUERY_PARSE_FAILED)，
 * 绝不交给数据库试运行（docs/06 §7.3 强制拒绝项：COPY/DO/未知函数/锁语句等先解析再分类，
 * 无法解析者等价于拒绝）。解析器版本经 {@link #parserVersion()} 锁定返回，作为语料回归基线。</p>
 *
 * <p><b>动作分类</b>（docs/06 §7.2、§7.3）映射到冻结的 {@link DbAction}：
 * SELECT→QUERY、EXPLAIN（不含 ANALYZE）→EXPLAIN、SELECT INTO/CREATE TABLE AS→CHANGE_DDL、
 * DML→CHANGE_DML、DDL→CHANGE_DDL、SET/VACUUM/REINDEX/CLUSTER/SET ROLE/ALTER SYSTEM→ADMIN、
 * CALL/DO→CODE。EXPLAIN ANALYZE 会真实执行语句，归 readonly=false（CHANGE_DML），
 * 普通用户经网关只读门禁拒绝（docs/06 §7.5）。</p>
 *
 * <p><b>资源路径</b>：PostgreSQL 有独立 schema 层，规范路径为
 * {@code /db/<db>/schema/<s>/table/<t>}（catalog 可见时）或 {@code /schema/<s>/table/<t>}
 *（仅 schema 限定）或 {@code /table/<t>}（无限定，由编排者用 search_path/默认库补全，
 * 跨 lane 集成决策，与 MySQL 一致待集成者确认）。列追加 {@code /col/<c>}。</p>
 *
 * @author DataGate
 */
public class PostgresqlQueryParser implements QueryParser {

    /** 批次上限（docs/06 §4：批次 P0 上限 20 条）。 */
    private static final int MAX_BATCH = 20;

    /** 解析器版本（语料回归基线，docs/06 §5.1）。 */
    private static final String PARSER_VERSION = "druid-" + VERSION.getVersionNumber() + "/postgresql";

    /**
     * 已知无副作用的 PostgreSQL 内建函数白名单（QRY-102 / docs/06 §7.3「执行未知函数」失败关闭）。
     * 不在 {@link #SAFE_FUNCTIONS} 且不在 {@link #FORBIDDEN_FUNCTIONS} 的函数调用视为「未知函数」
     * （可能是用户定义/SECURITY DEFINER 函数），按 docs/06 §7.3 失败关闭：SELECT 语句转为 readonly=false。
     * 白名单覆盖 P0 常用分析函数；执行器 BEGIN READ ONLY 事务作为写动作纵深防线。
     * 用 Arrays.asList 装填 HashSet（容错重复，避免 Set.of 抛 IllegalArgumentException）。
     */
    private static final Set<String> SAFE_FUNCTIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        // 字符串
        "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "OCTET_LENGTH", "SUBSTRING", "SUBSTR",
        "POSITION", "OVERLAY", "TRIM", "BTRIM", "LTRIM", "RTRIM", "LPAD", "RPAD", "REPEAT",
        "REPLACE", "TRANSLATE", "CONCAT", "CONCAT_WS", "FORMAT", "LEFT", "RIGHT", "REVERSE",
        "REGEXP_MATCHES", "REGEXP_REPLACE", "REGEXP_SPLIT_TO_ARRAY", "REGEXP_SPLIT_TO_TABLE",
        "REGEXP_COUNT", "REGEXP_INSTR", "REGEXP_LIKE", "REGEXP_SUBSTR", "SPLIT_PART",
        "STRPOS", "ASCII", "CHR", "INITCAP", "LOWER", "UPPER", "QUOTE_IDENT",
        "QUOTE_LITERAL", "QUOTE_NULLABLE", "ENCODE", "DECODE",
        // 数学
        "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "TRUNC", "SIGN", "MOD", "POWER", "SQRT",
        "CBRT", "EXP", "LN", "LOG", "LOG10", "LOG2", "PI", "DEGREES", "RADIANS", "RANDOM",
        "GREATEST", "LEAST", "WIDTH_BUCKET", "DIV", "FACTORIAL", "GCD", "LCM",
        "ACOS", "ASIN", "ATAN", "ATAN2", "COS", "COT", "SIN", "TAN", "SIND", "COSD", "TAND",
        // 日期时间
        "AGE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP",
        "NOW", "TRANSACTION_TIMESTAMP", "STATEMENT_TIMESTAMP", "CLOCK_TIMESTAMP",
        "TIMEOFDAY", "DATE_PART", "DATE_TRUNC", "EXTRACT", "JUSTIFY_DAYS", "JUSTIFY_HOURS",
        "JUSTIFY_INTERVAL", "MAKE_DATE", "MAKE_TIME", "MAKE_TIMESTAMPTZ", "MAKE_TIMESTAMP",
        "MAKE_INTERVAL", "TO_CHAR", "TO_DATE", "TO_NUMBER", "TO_TIMESTAMP",
        "ISFINITE", "DATE_BIN", "EXTRACT_EPOCH",
        // 聚合 / 窗口 / 统计
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ARRAY_AGG", "STRING_AGG", "XMLAGG", "JSONB_AGG",
        "JSON_AGG", "JSONB_OBJECT_AGG", "JSON_OBJECT_AGG", "CORR", "COVAR_POP", "COVAR_SAMP",
        "REGR_AVGX", "REGR_AVGY", "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE",
        "REGR_SXX", "REGR_SXY", "REGR_SYY", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
        "VAR_POP", "VAR_SAMP", "VARIANCE", "PERCENTILE_CONT", "PERCENTILE_DISC", "MODE",
        "RANK", "DENSE_RANK", "PERCENT_RANK", "CUME_DIST", "ROW_NUMBER", "NTILE", "LAG", "LEAD",
        "FIRST_VALUE", "LAST_VALUE", "NTH_VALUE", "BOOL_AND", "BOOL_OR",
        "EVERY", "BIT_AND", "BIT_OR", "GROUPING",
        // JSON / JSONB
        "JSONB_EXTRACT_PATH", "JSONB_EXTRACT_PATH_TEXT", "JSON_EXTRACT_PATH", "JSON_EXTRACT_PATH_TEXT",
        "JSONB_ARRAY_LENGTH", "JSON_ARRAY_LENGTH", "JSONB_OBJECT_KEYS", "JSON_OBJECT_KEYS",
        "JSONB_PRETTY", "JSONB_TYPE", "JSON_TYPE", "JSONB_EXISTS", "JSON_EXISTS",
        "JSONB_INSERT", "JSONB_SET", "JSONB_DELETE", "JSONB_STRIP_NULLS", "JSONB_PATH_EXISTS",
        "JSONB_PATH_MATCH", "JSONB_PATH_QUERY", "JSONB_PATH_QUERY_ARRAY", "JSONB_PATH_EXISTS_TZ",
        "ROW_TO_JSON", "ARRAY_TO_JSON", "TO_JSON", "TO_JSONB", "JSONB_BUILD_ARRAY", "JSONB_BUILD_OBJECT",
        "JSON_BUILD_ARRAY", "JSON_BUILD_OBJECT",
        // 数组 / 集合
        "ARRAY_LENGTH", "ARRAY_LENGTHS", "ARRAY_LOWER", "ARRAY_UPPER", "ARRAY_PREPEND",
        "ARRAY_APPEND", "ARRAY_CAT", "ARRAY_POSITION", "ARRAY_POSITIONS", "ARRAY_REMOVE",
        "ARRAY_REPLACE", "ARRAY_TO_STRING", "ARRAY_TO_TSVECTOR", "ARRAY_NDIMS", "CARDINALITY",
        "UNNEST", "ARRAY_FILL", "ARRAY_DIMS", "GENERATE_SERIES", "GENERATE_SUBSCRIPTS", "SETSEED",
        // 类型转换 / 空值 / 条件 / 比较
        "CAST", "ROW", "COALESCE", "NULLIF", "CASE", "ARRAY",
        "TO_HEX", "BIT_LENGTH", "TO_ASCII", "PG_CLIENT_ENCODING", "PG_COLLATION_FOR",
        "PG_COLUMN_SIZE", "TYPEID",
        // 信息（只读，不暴露账号敏感信息）
        "VERSION", "CURRENT_USER", "CURRENT_SCHEMA", "CURRENT_SCHEMAS", "SESSION_USER",
        "CURRENT_DATABASE", "CURRENT_QUERY", "PG_BACKEND_PID",
        "PG_POSTMASTER_START_TIME", "PG_CONF_LOAD_TIME", "PG_IS_IN_RECOVERY",
        "PG_LAST_XACT_REPLAY_TIMESTAMP", "PG_CURRENT_SNAPSHOT", "PG_CURRENT_WAL_LSN",
        "PG_LAST_WAL_REPLAY_LSN", "PG_WAL_LSN_DIFF",
        // 加密摘要（只读、确定性输出）
        "MD5", "SHA224", "SHA256", "SHA384", "SHA512", "HMAC", "DIGEST", "CRYPT"
    )));

    /**
     * 已知有副作用 / 文件访问 / DoS 的 PostgreSQL 内建函数（docs/06 §7.3 强制拒绝项）。
     * pg_read_file/pg_ls_dir/lo_import/lo_export/dblink → 文件/外部程序访问 → EXPORT；
     * 其余 → CHANGE_DML（潜在写入/副作用/锁/管理）。
     */
    private static final Set<String> FILE_FUNCTIONS = Set.of(
        "PG_READ_FILE", "PG_READ_BINARY_FILE", "PG_LS_DIR", "PG_LS_LOGDIR",
        "LO_IMPORT", "LO_EXPORT", "DBLINK", "DBLINK_CONNECT", "DBLINK_DISCONNECT"
    );

    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
        // 管理副作用 / 锁 / 终止后端
        "PG_SLEEP", "PG_SLEEP_FOR", "PG_SLEEP_UNTIL",
        "PG_ADVISORY_LOCK", "PG_ADVISORY_UNLOCK", "PG_ADVISORY_UNLOCK_ALL",
        "PG_TRY_ADVISORY_LOCK", "PG_TRY_ADVISORY_XACT_LOCK", "PG_ADVISORY_XACT_LOCK",
        "PG_ADVISORY_XACT_LOCK_TRY", "PG_ADVISORY_LOCK_SHARED",
        "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND", "PG_RELOAD_CONF", "PG_ROTATE_LOGFILE",
        "PG_SWITCH_WAL", "PG_CREATE_RESTORE_POINT", "PG_PROMOTE", "PG_LOG_BACKEND_DATA_SNAPSHOT",
        "PG_STAT_RESET", "PG_STAT_RESET_SINGLE_TABLE_COUNTERS", "PG_STAT_RESET_SINGLE_FUNCTION_COUNTERS",
        "PG_STAT_RESET_REPLICATOR", "PG_STATRESET", "PG_REPLICATION_ORIGIN_ADVANCE",
        "PG_REPLICATION_ORIGIN_CREATE", "PG_REPLICATION_ORIGIN_DROP", "PG_REPLICATION_ORIGIN_OID",
        "PG_REPLICATION_ORIGIN_PROGRESS", "PG_REPLICATION_ORIGIN_SESSION_RESET",
        "PG_REPLICATION_ORIGIN_SESSION_SETUP", "PG_REPLICATION_ORIGIN_SESSION_PROGRESS",
        "PG_REPLICATION_ORIGIN_XSYNC", "PG_REPLICATION_ORIGIN_XSET", "PG_CURRENT_XLOG_LOCATION",
        "PG_PREPARE", "PG_EXECUTE", "PG_DEALLOCATE"
    );

    @Override
    public List<ParsedStatement> parse(String statement) {
        if (statement == null || statement.isBlank()) {
            throw fail("空语句", null);
        }
        List<SQLStatement> stmts = new ArrayList<>();
        PGSQLStatementParser parser = new PGSQLStatementParser(statement);
        try {
            parser.parseStatementList(stmts, MAX_BATCH);
        } catch (RuntimeException e) {
            // 解析阶段异常：未知方言/PL/pgSQL 块/客户端元命令 → 失败关闭
            throw fail("解析失败", e);
        }
        if (stmts.isEmpty()) {
            throw fail("无可解析语句", null);
        }
        // 未消费全部输入：语法残留/未知方言/批次超上限 → 失败关闭
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
        // EXPLAIN ANALYZE：真实执行语句，普通用户禁止（docs/06 §7.5）
        if (stmt instanceof SQLExplainAnalyzeStatement) {
            ResourceCollector rc = new ResourceCollector();
            stmt.accept(rc);
            stmt.accept(rc);
            String normalized = normalize(stmt);
            return new ParsedStatement("EXPLAIN_ANALYZE", rc.buildResourcePaths(), normalized,
                "postgresql:" + sha256(normalized), DbAction.CHANGE_DML, false);
        }
        // EXPLAIN（不含 ANALYZE）：不执行原语句，readonly=true（docs/06 §7.5）
        if (stmt instanceof SQLExplainStatement expl) {
            return parseExplain(expl);
        }
        // COPY FROM：从文件/PROGRAM 导入数据 → CHANGE_DML（docs/06 §7.3 COPY）
        if (stmt instanceof SQLCopyFromStatement) {
            String normalized = normalize(stmt);
            return new ParsedStatement("COPY", List.of(), normalized,
                "postgresql:" + sha256(normalized), DbAction.CHANGE_DML, false);
        }
        // REFRESH MATERIALIZED VIEW：物化视图刷新 → CHANGE_DDL（docs/06 §7.3）
        if (stmt instanceof SQLRefreshMaterializedViewStatement) {
            String normalized = normalize(stmt);
            return new ParsedStatement("REFRESH", List.of(), normalized,
                "postgresql:" + sha256(normalized), DbAction.CHANGE_DDL, false);
        }

        Classification base = classify(stmt);

        // 两遍 AST 遍历：构建别名/CTE 表，再用完整别名表解析 SELECT 列引用（同 MySQL）
        ResourceCollector rc = new ResourceCollector();
        stmt.accept(rc);
        stmt.accept(rc);
        if (stmt instanceof SQLShowStatement) {
            rc.collectShow(stmt);
        }

        DbAction action = base.action;
        boolean readonly = base.readonly;
        // SELECT 系（readonly 上下文）出现强制拒绝信号 → 升级风险（docs/06 §7.3）
        if (readonly) {
            if (rc.intoClause) {
                // SELECT INTO → 创建表 → CHANGE_DDL（docs/06 §7.3）
                action = DbAction.CHANGE_DDL;
                readonly = false;
            } else if (rc.forClause) {
                // FOR UPDATE/FOR SHARE/FOR KEY SHARE/FOR NO KEY UPDATE → CHANGE_DML
                action = DbAction.CHANGE_DML;
                readonly = false;
            } else if (rc.fileFunction) {
                // pg_read_file/lo_import/dblink 等 → EXPORT
                action = DbAction.EXPORT;
                readonly = false;
            } else if (rc.forbiddenFunction) {
                // 副作用/DoS/未知函数 → CHANGE_DML（docs/06 §7.3「执行未知函数」失败关闭）
                action = DbAction.CHANGE_DML;
                readonly = false;
            }
        }

        List<String> paths = rc.buildResourcePaths();
        String normalized = normalize(stmt);
        String fingerprint = "postgresql:" + sha256(normalized);
        return new ParsedStatement(base.statementType, paths, normalized, fingerprint, action, readonly);
    }

    private ParsedStatement parseExplain(SQLExplainStatement expl) {
        SQLStatement explained = expl.getStatement();
        ResourceCollector rc = new ResourceCollector();
        if (explained != null) {
            explained.accept(rc);
            explained.accept(rc);
        }
        String normalized = normalize(expl);
        // EXPLAIN ANALYZE：真实执行语句，普通用户禁止（docs/06 §7.5）。
        // Druid 将 EXPLAIN ANALYZE 解析为 SQLExplainStatement，ANALYZE 标记存于 type/format 字段。
        if (isAnalyze(expl)) {
            return new ParsedStatement("EXPLAIN_ANALYZE", rc.buildResourcePaths(), normalized,
                "postgresql:" + sha256(normalized), DbAction.CHANGE_DML, false);
        }
        return new ParsedStatement("EXPLAIN", rc.buildResourcePaths(), normalized,
            "postgresql:" + sha256(normalized), DbAction.EXPLAIN, true);
    }

    /** 检测 EXPLAIN 是否请求 ANALYZE（执行原语句，docs/06 §7.5）。 */
    private static boolean isAnalyze(SQLExplainStatement expl) {
        String t = expl.getType();
        if (t != null && t.toUpperCase().contains("ANALYZE")) {
            return true;
        }
        String f = expl.getFormat();
        return f != null && f.toUpperCase().contains("ANALYZE");
    }

    /**
     * 按 AST 节点类型给出基础分类（docs/06 §7.2、§7.3）。未知类型失败关闭。
     */
    private Classification classify(SQLStatement stmt) {
        if (stmt instanceof SQLSelectStatement || stmt instanceof PGSelectStatement) {
            return new Classification("SELECT", DbAction.QUERY, true);
        }
        if (stmt instanceof SQLInsertStatement || stmt instanceof PGInsertStatement) {
            return new Classification("INSERT", DbAction.CHANGE_DML, false);
        }
        if (stmt instanceof SQLUpdateStatement || stmt instanceof PGUpdateStatement) {
            return new Classification("UPDATE", DbAction.CHANGE_DML, false);
        }
        if (stmt instanceof SQLDeleteStatement || stmt instanceof PGDeleteStatement) {
            return new Classification("DELETE", DbAction.CHANGE_DML, false);
        }
        if (stmt instanceof SQLMergeStatement) {
            return new Classification("MERGE", DbAction.CHANGE_DML, false);
        }
        // CALL：CODE 类，P0 禁止（docs/06 §7.3、ADR-007 修订）
        if (stmt instanceof SQLCallStatement) {
            return new Classification("CALL", DbAction.CODE, false);
        }
        // SET：含 SET ROLE/SET SESSION AUTHORIZATION，统一归管理动作 ADMIN（docs/06 §7.3）
        if (stmt instanceof SQLSetStatement) {
            return new Classification("SET", DbAction.ADMIN, false);
        }
        // SHOW 安全参数（docs/06 §7.2）：归 METADATA_READ 只读
        if (stmt instanceof SQLShowStatement || stmt instanceof PGShowStatement) {
            return new Classification("SHOW", DbAction.METADATA_READ, true);
        }
        // 管理动作（§7.3 强制拒绝项）：GRANT/REVOKE → ADMIN
        if (stmt instanceof SQLGrantStatement || stmt instanceof SQLRevokeStatement) {
            return new Classification(adminVerb(stmt), DbAction.ADMIN, false);
        }
        // DDL 家族：CREATE/ALTER/DROP/TRUNCATE/RENAME（含 SELECT INTO 的目标表创建由 visitor 信号升级）
        String verb = ddlVerb(stmt);
        if (verb != null) {
            return new Classification(verb, DbAction.CHANGE_DDL, false);
        }
        // VACUUM/REINDEX/CLUSTER/ANALYZE/COMMENT 等 PG 管理动作（Druid 若能解析其节点名）
        String cn = stmt.getClass().getSimpleName();
        if (cn.contains("Vacuum") || cn.contains("Reindex") || cn.contains("Cluster")
            || cn.contains("Comment") || cn.contains("Truncate") || cn.contains("Lock")
            || cn.contains("Listen") || cn.contains("Notify") || cn.contains("Load")) {
            return new Classification(adminVerb(stmt), DbAction.ADMIN, false);
        }
        // 未知语法 → 失败关闭
        throw fail("不支持的语句类型: " + stmt.getClass().getSimpleName(), null);
    }

    /** DDL 家族动词判定（按 AST 节点的 Java 类名前缀）。 */
    private String ddlVerb(SQLStatement stmt) {
        String cn = stmt.getClass().getSimpleName();
        if (cn.startsWith("SQLCreate") || cn.startsWith("PGCreate")) {
            return "CREATE";
        }
        if (cn.startsWith("SQLAlter") || cn.startsWith("PGAlter")) {
            return "ALTER";
        }
        if (cn.startsWith("SQLDrop") || cn.startsWith("PGDrop")) {
            return "DROP";
        }
        if (cn.startsWith("SQLTruncate") || cn.startsWith("PGTruncate")) {
            return "TRUNCATE";
        }
        if (cn.startsWith("SQLRename") || cn.startsWith("PGRename")) {
            return "RENAME";
        }
        return null;
    }

    private String adminVerb(SQLStatement stmt) {
        String cn = stmt.getClass().getSimpleName();
        if (cn.contains("Grant")) {
            return "GRANT";
        }
        if (cn.contains("Revoke")) {
            return "REVOKE";
        }
        if (cn.contains("Vacuum")) {
            return "VACUUM";
        }
        if (cn.contains("Reindex")) {
            return "REINDEX";
        }
        if (cn.contains("Cluster")) {
            return "CLUSTER";
        }
        if (cn.contains("Comment")) {
            return "COMMENT";
        }
        if (cn.contains("Listen")) {
            return "LISTEN";
        }
        if (cn.contains("Notify")) {
            return "NOTIFY";
        }
        if (cn.contains("Load")) {
            return "LOAD";
        }
        if (cn.contains("Lock")) {
            return "LOCK";
        }
        return "ADMIN";
    }

    // ====================== 归一化与指纹 ======================

    /** 去常量归一化语句（Druid ParameterizedOutputVisitor，QRY-103）。 */
    private String normalize(SQLStatement stmt) {
        try {
            String p = ParameterizedOutputVisitorUtils.parameterize(stmt, com.alibaba.druid.DbType.postgresql);
            if (p != null && !p.isBlank()) {
                return p;
            }
        } catch (RuntimeException ignored) {
            // 参数化失败时回退到 AST 还原
        }
        try {
            return SQLUtils.toPGString(stmt);
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
     * AST 访问者：提取 db/schema/table/column 规范资源路径，并在 SELECT 上下文中检测
     * SELECT INTO、FOR UPDATE/SHARE/KEY SHARE/NO KEY UPDATE、副作用/文件/未知函数等强制拒绝信号
     * （docs/06 §7.3）。
     *
     * <p>资源路径风格与 {@link org.dromara.db.core.domain.ResourceNode#canonicalPath()} 一致：
     * PostgreSQL 有独立 schema 层——catalog+schema 可见时为
     * {@code /db/<db>/schema/<s>/table/<t>}；仅 schema 限定为 {@code /schema/<s>/table/<t>}；
     * 无限定为 {@code /table/<t>}（由编排者用 search_path/默认库补全）。列追加 {@code /col/<c>}。</p>
     */
    private static final class ResourceCollector extends PGASTVisitorAdapter {

        private final LinkedHashSet<String> tables = new LinkedHashSet<>();
        private final LinkedHashSet<String> columns = new LinkedHashSet<>();
        /** 别名/表名 → 表路径（key 小写）。 */
        private final HashMap<String, String> aliasToPath = new HashMap<>();
        private final HashSet<String> cteAliases = new HashSet<>();

        private boolean intoClause;
        private boolean forClause;
        private boolean fileFunction;
        private boolean forbiddenFunction;

        @Override
        public boolean visit(SQLWithSubqueryClause x) {
            for (SQLWithSubqueryClause.Entry entry : x.getEntries()) {
                String alias = entry.getAlias();
                if (alias != null && !alias.isBlank()) {
                    cteAliases.add(match(alias));
                }
            }
            return true;
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            SQLName name = x.getName();
            if (name == null) {
                return true;
            }
            String table = x.getTableName();
            if (table == null || table.isBlank()) {
                return true;
            }
            String matchTable = match(table);
            if (cteAliases.contains(matchTable)) {
                String alias = x.getAlias();
                if (alias != null && !alias.isBlank()) {
                    aliasToPath.put(match(alias), null);
                }
                return true;
            }
            String schema = x.getSchema();
            String catalog = x.getCatalog();
            String tablePath = buildTablePath(catalog, schema, table);
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
            // SELECT INTO（通用）：getInto 非空
            if (x.getInto() != null) {
                intoClause = true;
            }
            // FOR UPDATE / FOR SHARE（通用基类）
            if (x.isForUpdate() || x.isForShare()) {
                forClause = true;
            }
            // PG 专属：SELECT INTO（getIntoOption）与 FOR 子句
            if (x instanceof PGSelectQueryBlock pg) {
                if (pg.getIntoOption() != null) {
                    intoClause = true;
                }
                if (pg.getForClause() != null) {
                    // FOR UPDATE / FOR SHARE / FOR NO KEY UPDATE / FOR KEY SHARE 均归锁定
                    forClause = true;
                }
            }
            return true;
        }

        @Override
        public boolean visit(PGSelectQueryBlock x) {
            visit((SQLSelectQueryBlock) x);
            return true;
        }

        @Override
        public boolean visit(SQLPropertyExpr x) {
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
            if (FILE_FUNCTIONS.contains(up)) {
                fileFunction = true;
            } else if (FORBIDDEN_FUNCTIONS.contains(up)) {
                forbiddenFunction = true;
            } else if (!SAFE_FUNCTIONS.contains(up)) {
                // 未知函数（可能用户定义/SECURITY DEFINER）→ 失败关闭（docs/06 §7.3「执行未知函数」）
                forbiddenFunction = true;
            }
            return true;
        }

        /** SHOW 引用资源提取（docs/06 §7.2，PG SHOW 安全参数，不暴露账号/权限信息）。 */
        void collectShow(SQLStatement stmt) {
            // PG SHOW 多为参数查看（SHOW search_path 等），无表资源引用；保守不产出。
            // 非法/不安全 SHOW 由分类层保守处理（Druid 不支持的 SHOW 形态会解析失败 → 失败关闭）。
        }

        List<String> buildResourcePaths() {
            List<String> all = new ArrayList<>(tables);
            all.addAll(columns);
            return all;
        }

        /** 构建含 schema 层的表路径。 */
        private static String buildTablePath(String catalog, String schema, String table) {
            boolean hasCat = catalog != null && !catalog.isBlank();
            boolean hasSchema = schema != null && !schema.isBlank();
            if (hasCat && hasSchema) {
                return "/db/" + path(catalog) + "/schema/" + path(schema) + "/table/" + path(table);
            }
            if (hasSchema) {
                return "/schema/" + path(schema) + "/table/" + path(table);
            }
            if (hasCat) {
                return "/db/" + path(catalog) + "/table/" + path(table);
            }
            return "/table/" + path(table);
        }

        private static String match(String id) {
            return stripQuotes(id).toLowerCase();
        }

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
                if ((f == '"' && l == '"') || (f == '`' && l == '`')) {
                    return s.substring(1, s.length() - 1);
                }
            }
            return s;
        }
    }
}
