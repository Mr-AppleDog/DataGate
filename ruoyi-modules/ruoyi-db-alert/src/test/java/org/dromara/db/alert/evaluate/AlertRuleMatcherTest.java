package org.dromara.db.alert.evaluate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警规则匹配器测试（docs/07 §7：scope 维度匹配、metric 取值、operator 比较、COLLECTOR 分流）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("告警规则匹配器")
class AlertRuleMatcherTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private SlowMetricEvent bucket(long ds, String env, String engine, long max, long p95, long total, long count, boolean firstSeen) {
        return new SlowMetricEvent(ds, 1L, 10L, "fp1", "SELECT * FROM t WHERE id = ?", engine, "db1", env,
            1000L, 60000L, count, 0, total, max, p95, null, null, null, firstSeen, 0, false);
    }

    private SlowMetricEvent collector(long ds, int failures) {
        return new SlowMetricEvent(ds, 1L, null, null, null, "MYSQL", null, "prod",
            0L, 0L, 0, 0, 0, 0, 0, null, null, null, false, failures, true);
    }

    private DbAlertRule rule(String metric, String op, long threshold, String scope, String firstSeen) {
        DbAlertRule r = new DbAlertRule();
        r.setId(1L);
        r.setMetric(metric);
        r.setOperator(op);
        r.setThreshold(BigDecimal.valueOf(threshold));
        r.setScope(scope);
        r.setFirstSeenOnly(firstSeen);
        return r;
    }

    private String scope(String env) {
        if (env == null) return "{}";
        try {
            return OM.writeValueAsString(Map.of("environment", env));
        } catch (Exception e) {
            return "{}";
        }
    }

    @Test
    @DisplayName("P1 单次最大耗时 ≥ 30s 触发")
    void p1SingleMaxTriggered() {
        var r = rule("SINGLE_MAX_DURATION", "GE", 30000000, scope("prod"), "0");
        var m = bucket(1, "prod", "MYSQL", 35000000, 0, 0, 1, false);
        var res = AlertRuleMatcher.evaluate(r, m);
        assertTrue(res.triggered());
        assertEquals(35000000, res.value().longValue());
    }

    @Test
    @DisplayName("单次最大耗时 < 30s 不触发")
    void p1SingleMaxNotTriggered() {
        var r = rule("SINGLE_MAX_DURATION", "GE", 30000000, scope("prod"), "0");
        var m = bucket(1, "prod", "MYSQL", 10000000, 0, 0, 1, false);
        var res = AlertRuleMatcher.evaluate(r, m);
        assertFalse(res.triggered());
    }

    @Test
    @DisplayName("P2 窗口 P95 ≥ 5s 触发")
    void p2WindowP95Triggered() {
        var r = rule("WINDOW_P95", "GE", 5000000, scope("prod"), "0");
        var m = bucket(1, "prod", "MYSQL", 0, 6000000, 0, 25, false);
        var res = AlertRuleMatcher.evaluate(r, m);
        assertTrue(res.triggered());
    }

    @Test
    @DisplayName("COLLECTOR 连续 3 失败触发（collectorHealth 事件）")
    void collectorFailureTriggered() {
        var r = rule("COLLECTOR_FAILURE", "GE", 3, scope(null), "0");
        var res = AlertRuleMatcher.evaluate(r, collector(1, 3));
        assertTrue(res.triggered());
    }

    @Test
    @DisplayName("COLLECTOR 规则不对桶指标事件触发")
    void collectorNotForBucket() {
        var r = rule("COLLECTOR_FAILURE", "GE", 3, scope(null), "0");
        var res = AlertRuleMatcher.evaluate(r, bucket(1, "prod", "MYSQL", 99999999, 0, 0, 1, false));
        assertFalse(res.triggered());
    }

    @Test
    @DisplayName("scope 环境不匹配不触发（prod 规则 vs dev 指标）")
    void scopeEnvMismatchNotTriggered() {
        var r = rule("SINGLE_MAX_DURATION", "GE", 30000000, scope("prod"), "0");
        var m = bucket(1, "dev", "MYSQL", 35000000, 0, 0, 1, false);
        var res = AlertRuleMatcher.evaluate(r, m);
        assertFalse(res.triggered());
    }

    @Test
    @DisplayName("首次出现规则：非首次不触发")
    void firstSeenOnlyNotTriggered() {
        var r = rule("SINGLE_MAX_DURATION", "GE", 3000000, scope(null), "1");
        var m = bucket(1, "prod", "MYSQL", 5000000, 0, 0, 1, false);
        assertFalse(AlertRuleMatcher.evaluate(r, m).triggered());
    }

    @Test
    @DisplayName("首次出现规则：首次 + 满足阈值触发")
    void firstSeenOnlyTriggered() {
        var r = rule("SINGLE_MAX_DURATION", "GE", 3000000, scope(null), "1");
        var m = bucket(1, "prod", "MYSQL", 5000000, 0, 0, 1, true);
        assertTrue(AlertRuleMatcher.evaluate(r, m).triggered());
    }
}
