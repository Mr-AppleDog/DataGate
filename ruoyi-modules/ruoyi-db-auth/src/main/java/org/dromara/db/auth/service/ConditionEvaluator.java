package org.dromara.db.auth.service;

import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.support.CidrMatcher;
import org.dromara.db.core.authz.DecisionRequest;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 授权条件评估器（docs/03 第 6、7.2 节 step 6/7，AUTH）。
 *
 * <p>仅评估“门控型”条件（sourceIpCidr/timeWindow/requireMfa/requireRecentReauth）是否满足；
 * 限制型条件（maxRows/maxBytes/maxExecutionSeconds/maskingLevel）由鉴权服务单独提取做限制合并。
 * 任一门控条件不满足即视为该授权未完整满足。无门控条件时返回 true。</p>
 *
 * @author DataGate
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * 评估授权的门控条件是否满足。
     *
     * @param grant  授权
     * @param request 判定请求
     * @param now     当前时间（UTC）
     * @return true=所有门控条件满足（或无门控条件）
     */
    public static boolean satisfied(Grant grant, DecisionRequest request, Instant now) {
        Map<String, Object> c = grant.getConditions();
        if (c == null || c.isEmpty()) {
            return true;
        }
        if (!sourceIpOk(c, request)) {
            return false;
        }
        if (!timeWindowOk(c, now)) {
            return false;
        }
        if (!mfaOk(c, request)) {
            return false;
        }
        return recentReauthOk(c, request, now);
    }

    private static boolean sourceIpOk(Map<String, Object> c, DecisionRequest request) {
        Object v = c.get("sourceIpCidr");
        if (v == null) {
            return true;
        }
        List<String> cidrs = asStringList(v);
        if (cidrs.isEmpty()) {
            return true;
        }
        if (request.sourceIp() == null) {
            return false; // 要求 IP 段但请求无 IP：失败关闭
        }
        for (String cidr : cidrs) {
            if (CidrMatcher.matches(request.sourceIp(), cidr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean timeWindowOk(Map<String, Object> c, Instant now) {
        Object v = c.get("timeWindow");
        if (v == null) {
            return true;
        }
        Map<String, Object> tw = asMap(v);
        Object start = tw.get("start");
        Object end = tw.get("end");
        if (start == null || end == null) {
            return true; // 配置不全视为不限制
        }
        LocalTime s = parseTime(start);
        LocalTime e = parseTime(end);
        if (s == null || e == null) {
            return false; // 非法时间配置：失败关闭
        }
        LocalTime t = now.atZone(ZoneOffset.UTC).toLocalTime();
        return !t.isBefore(s) && t.isBefore(e);
    }

    private static boolean mfaOk(Map<String, Object> c, DecisionRequest request) {
        Object v = c.get("requireMfa");
        if (!Boolean.TRUE.equals(toBoolean(v))) {
            return true;
        }
        Map<String, Object> ctx = request.requestContext();
        return Boolean.TRUE.equals(toBoolean(ctx.get("mfaVerified")));
    }

    private static boolean recentReauthOk(Map<String, Object> c, DecisionRequest request, Instant now) {
        Object v = c.get("requireRecentReauth");
        if (v == null) {
            return true;
        }
        long seconds = toLong(v);
        if (seconds <= 0) {
            return true;
        }
        Map<String, Object> ctx = request.requestContext();
        Object reauth = ctx.get("reauthAtEpochMillis");
        Long reauthMs = toLongOrNull(reauth);
        if (reauthMs == null) {
            return false;
        }
        long elapsed = now.toEpochMilli() - reauthMs;
        return elapsed >= 0 && elapsed <= seconds * 1000L;
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        if (v instanceof Collection<?> col) {
            return col.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static LocalTime parseTime(Object v) {
        try {
            return LocalTime.parse(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Long toLongOrNull(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
