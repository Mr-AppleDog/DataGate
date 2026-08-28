package org.dromara.db.core.change;

import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;

import java.util.ArrayList;
import java.util.List;

/**
 * 变更预检查风险分析器（docs/06 §13、docs/05 §2.8 :precheck，M5-02）。
 *
 * <p>纯静态方法：对已解析的 DML/DDL 语句计算高风险标签——无 WHERE 的 UPDATE/DELETE、
 * 大表/破坏性 DDL（DROP/TRUNCATE）、锁风险（ALTER）、全表扫描可能性。结果供审批人决策，
 * 最终安全由审批 + 专用变更账号有限授权保证（docs/06 §13 不做自动 SQL 优化或自动回滚）。</p>
 *
 * @author DataGate
 */
public final class ChangeRiskAnalyzer {

    private ChangeRiskAnalyzer() {
    }

    public static final String RISK_NO_WHERE = "NO_WHERE_UPDATE_DELETE";
    public static final String RISK_DESTRUCTIVE_DDL = "DESTRUCTIVE_DDL";
    public static final String RISK_LOCK = "LOCK_RISK";
    public static final String RISK_DDL_NON_TRANSACTIONAL = "DDL_NON_TRANSACTIONAL";
    public static final String RISK_FULL_TABLE_SCAN = "FULL_TABLE_SCAN";

    public static final String SEVERITY_HIGH = "HIGH";
    public static final String SEVERITY_MEDIUM = "MEDIUM";
    public static final String SEVERITY_LOW = "LOW";

    /**
     * 单条语句风险分析。
     *
     * @param stmt 已解析语句
     * @return 风险标签列表（可空）
     */
    public static List<String> analyzeStatement(ParsedStatement stmt) {
        List<String> risks = new ArrayList<>();
        if (stmt == null) {
            return risks;
        }
        String type = stmt.statementType() == null ? "" : stmt.statementType().toUpperCase();
        String sql = stmt.normalizedStatement() == null ? "" : stmt.normalizedStatement().toUpperCase();
        // DML 风险
        if (type.equals("UPDATE") || type.equals("DELETE") || type.equals("MERGE")) {
            if (!sql.contains("WHERE")) {
                risks.add(RISK_NO_WHERE);
                risks.add(RISK_FULL_TABLE_SCAN);
            }
        }
        // DDL 风险
        if (stmt.requiredAction() == DbAction.CHANGE_DDL) {
            risks.add(RISK_DDL_NON_TRANSACTIONAL);
            if (type.equals("DROP") || type.equals("TRUNCATE")) {
                risks.add(RISK_DESTRUCTIVE_DDL);
            }
            if (type.equals("ALTER")) {
                risks.add(RISK_LOCK);
            }
        }
        return risks;
    }

    /**
     * 批量分析并取最高严重度。
     */
    public static AnalysisResult analyze(List<ParsedStatement> stmts) {
        List<String> allRisks = new ArrayList<>();
        if (stmts != null) {
            for (ParsedStatement s : stmts) {
                allRisks.addAll(analyzeStatement(s));
            }
        }
        String severity = SEVERITY_LOW;
        if (allRisks.contains(RISK_DESTRUCTIVE_DDL) || allRisks.contains(RISK_NO_WHERE)) {
            severity = SEVERITY_HIGH;
        } else if (!allRisks.isEmpty()) {
            severity = SEVERITY_MEDIUM;
        }
        return new AnalysisResult(allRisks, severity);
    }

    /**
     * 预检查结果。
     *
     * @param risks   风险标签
     * @param severity 严重度 LOW/MEDIUM/HIGH
     */
    public record AnalysisResult(List<String> risks, String severity) {
    }
}
