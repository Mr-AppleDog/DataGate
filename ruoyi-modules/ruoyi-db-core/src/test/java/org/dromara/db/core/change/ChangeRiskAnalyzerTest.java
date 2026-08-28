package org.dromara.db.core.change;

import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.DbAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变更预检查风险分析单元测试（docs/06 §13、docs/05 §2.8，M5-02）。
 *
 * @author DataGate
 */
@Tag("unit")
class ChangeRiskAnalyzerTest {

    private ParsedStatement dml(String type, String normalized, boolean hasWhere) {
        return new ParsedStatement(type, List.of("/db/d/t"), hasWhere ? normalized + " WHERE id=?" : normalized,
            "fp", DbAction.CHANGE_DML, false);
    }

    private ParsedStatement ddl(String type, String normalized) {
        return new ParsedStatement(type, List.of("/db/d/t"), normalized, "fp", DbAction.CHANGE_DDL, false);
    }

    @Test
    void update_without_where_is_high_risk() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(dml("UPDATE", "UPDATE t SET x=?", false));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_NO_WHERE));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_FULL_TABLE_SCAN));
    }

    @Test
    void update_with_where_safe() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(dml("UPDATE", "UPDATE t SET x=?", true));
        assertTrue(risks.isEmpty());
    }

    @Test
    void delete_without_where_is_high_risk() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(dml("DELETE", "DELETE FROM t", false));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_NO_WHERE));
    }

    @Test
    void drop_is_destructive_ddl() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(ddl("DROP", "DROP TABLE t"));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_DESTRUCTIVE_DDL));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_DDL_NON_TRANSACTIONAL));
    }

    @Test
    void truncate_is_destructive() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(ddl("TRUNCATE", "TRUNCATE TABLE t"));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_DESTRUCTIVE_DDL));
    }

    @Test
    void alter_has_lock_risk() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(ddl("ALTER", "ALTER TABLE t ADD COLUMN c INT"));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_LOCK));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_DDL_NON_TRANSACTIONAL));
    }

    @Test
    void create_ddl_medium_risk() {
        var risks = ChangeRiskAnalyzer.analyzeStatement(ddl("CREATE", "CREATE TABLE t (c INT)"));
        assertTrue(risks.contains(ChangeRiskAnalyzer.RISK_DDL_NON_TRANSACTIONAL));
    }

    @Test
    void severity_aggregation_takes_highest() {
        var result = ChangeRiskAnalyzer.analyze(List.of(
            dml("UPDATE", "UPDATE t SET x=?", false),  // HIGH (NO_WHERE)
            ddl("CREATE", "CREATE TABLE t (c INT)"))); // MEDIUM
        assertEquals(ChangeRiskAnalyzer.SEVERITY_HIGH, result.severity());
        assertTrue(result.risks().contains(ChangeRiskAnalyzer.RISK_NO_WHERE));
        assertTrue(result.risks().contains(ChangeRiskAnalyzer.RISK_DDL_NON_TRANSACTIONAL));
    }

    @Test
    void safe_statements_low_risk() {
        var result = ChangeRiskAnalyzer.analyze(List.of(dml("UPDATE", "UPDATE t SET x=?", true)));
        assertEquals(ChangeRiskAnalyzer.SEVERITY_LOW, result.severity());
        assertTrue(result.risks().isEmpty());
    }

    @Test
    void null_safe() {
        var result = ChangeRiskAnalyzer.analyze(null);
        assertEquals(ChangeRiskAnalyzer.SEVERITY_LOW, result.severity());
        assertTrue(result.risks().isEmpty());
    }
}
