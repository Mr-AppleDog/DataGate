package org.dromara.db.alert.evaluate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.core.domain.SlowMetricEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 告警规则匹配器（docs/07 §7）。纯算法，不连数据库，可单测。
 *
 * 规则维度匹配（scope JSON：environment/dataSourceId/engine/database/fingerprint）；
 * 按 rule.metric 取指标值，按 operator 与 threshold 比较。
 * COLLECTOR_FAILURE 规则只对 collectorHealth 事件生效；其他规则跳过 collectorHealth 事件。
 *
 * @author DataGate
 */
public final class AlertRuleMatcher {

    private static final ObjectMapper OM = new ObjectMapper();

    public record MatchResult(boolean triggered, BigDecimal value) {
    }

    private AlertRuleMatcher() {
    }

    public static MatchResult evaluate(DbAlertRule rule, SlowMetricEvent m) {
        if (rule == null || m == null) {
            return new MatchResult(false, null);
        }
        if (!scopeMatches(rule.getScope(), m)) {
            return new MatchResult(false, null);
        }
        if ("COLLECTOR_FAILURE".equals(rule.getMetric())) {
            if (!m.collectorHealth()) {
                return new MatchResult(false, null);
            }
            return compare(rule, BigDecimal.valueOf(m.consecutiveFailures()));
        }
        if (m.collectorHealth()) {
            return new MatchResult(false, null);
        }
        BigDecimal value = metricValue(rule.getMetric(), m);
        if (value == null) {
            return new MatchResult(false, null);
        }
        if ("1".equals(rule.getFirstSeenOnly()) && !m.firstSeen()) {
            return new MatchResult(false, null);
        }
        return compare(rule, value);
    }

    private static boolean scopeMatches(String scopeJson, SlowMetricEvent m) {
        Map<String, Object> scope = parse(scopeJson);
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        if (!matchStr(scope.get("engine"), m.engine())) {
            return false;
        }
        if (!matchStr(scope.get("database"), m.database())) {
            return false;
        }
        if (!matchStr(scope.get("fingerprint"), m.fingerprint())) {
            return false;
        }
        if (!matchStr(scope.get("environment"), m.environment())) {
            return false;
        }
        Object ds = scope.get("dataSourceId");
        if (ds != null && m.dataSourceId() != null) {
            if (toLong(ds) != m.dataSourceId()) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal metricValue(String metric, SlowMetricEvent m) {
        switch (metric) {
            case "SINGLE_MAX_DURATION":
                return BigDecimal.valueOf(m.maxDurationMicros());
            case "WINDOW_COUNT":
                return BigDecimal.valueOf(m.eventCount());
            case "WINDOW_P95":
            case "WINDOW_P99":
                return BigDecimal.valueOf(m.p95DurationMicros());
            case "WINDOW_TOTAL_DURATION":
                return BigDecimal.valueOf(m.totalDurationMicros());
            case "LOCK_WAIT":
                return m.lockWaitMicros() == null ? null : BigDecimal.valueOf(m.lockWaitMicros());
            case "SCAN_RETURN_RATIO":
                if (m.rowsExamined() == null || m.rowsReturned() == null || m.rowsReturned() == 0) {
                    return null;
                }
                return BigDecimal.valueOf(m.rowsExamined())
                    .divide(BigDecimal.valueOf(m.rowsReturned()), 3, RoundingMode.HALF_UP);
            case "FIRST_SEEN":
                return m.firstSeen() ? BigDecimal.ONE : BigDecimal.ZERO;
            default:
                return null;
        }
    }

    private static MatchResult compare(DbAlertRule rule, BigDecimal value) {
        BigDecimal threshold = rule.getThreshold();
        if (threshold == null) {
            return new MatchResult(false, value);
        }
        String op = rule.getOperator();
        int cmp = value.compareTo(threshold);
        boolean triggered;
        switch (op == null ? "" : op) {
            case "GE":
                triggered = cmp >= 0;
                break;
            case "GT":
                triggered = cmp > 0;
                break;
            case "LE":
                triggered = cmp <= 0;
                break;
            case "LT":
                triggered = cmp < 0;
                break;
            default:
                triggered = false;
        }
        return new MatchResult(triggered, value);
    }

    private static boolean matchStr(Object scopeVal, String metricVal) {
        if (scopeVal == null) {
            return true;
        }
        return String.valueOf(scopeVal).equals(metricVal);
    }

    private static long toLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OM.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
