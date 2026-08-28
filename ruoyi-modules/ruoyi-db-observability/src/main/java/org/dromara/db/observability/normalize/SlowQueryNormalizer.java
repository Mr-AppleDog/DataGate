package org.dromara.db.observability.normalize;

import com.alibaba.druid.DbType;
import com.alibaba.druid.VERSION;
import com.alibaba.druid.sql.visitor.ParameterizedOutputVisitorUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 慢查询归一化引擎（docs/07 §5.1）。
 *
 * 职责：去注释/字面量占位/IN 折叠/大小写空白规范化 → 归一化模板；
 * 双指纹 portableFingerprint(SHA-256) + parserVersion（升级可追溯，不静默覆盖历史指纹）；
 * 敏感字面量清理（密码/邮箱/手机/身份证/Bearer/Token，日志通知永不发原文）；
 * 解析失败走保守哈希 + PARSE_FAILED；Redis/Tair 走命令模板归一化。
 *
 * 同一引擎版本对等价 SQL 保持稳定；normalizedSql 默认展示，原 SQL 单独加密（ADR-009 切片 B）。
 *
 * @author DataGate
 */
@Component
public class SlowQueryNormalizer {

    private static final String PARSER_VERSION = "druid-" + safeVersion();
    private static final String MASK = "?";

    // 敏感字面量（解析前后均清理，docs/07 §5.2）
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9])[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9])");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+\\S+");
    private static final Pattern SECRET_LIT = Pattern.compile(
        "(?i)(password|passwd|pwd|secret|token|apikey|access[_-]?key|authorization)\\s*[=:]\\s*['\"]?[^'\"\\s,;)]+");

    // 注释
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    // IN 列表（归一化后仍可能含 ?，用于元素数区间标签）
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bIN\\s*\\(([^)]*)\\)");

    /**
     * 归一化一条慢查询。
     *
     * @param engineType MYSQL/POSTGRESQL/REDIS/TAIR
     * @param rawSql     原始 SQL 或 Redis 命令文本
     */
    public NormalizedResult normalize(String engineType, String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return new NormalizedResult("", sha256(""), PARSER_VERSION, "PARSE_FAILED", "{}", "");
        }
        String engine = engineType == null ? "" : engineType.toUpperCase();
        if ("REDIS".equals(engine) || "TAIR".equals(engine)) {
            return normalizeRedisCommand(rawSql);
        }
        String sanitizedRaw = sanitize(rawSql);
        DbType dbType = "POSTGRESQL".equals(engine) ? DbType.postgresql : DbType.mysql;
        String normalized;
        String quality = "COMPLETE";
        try {
            String p = ParameterizedOutputVisitorUtils.parameterize(sanitizedRaw, dbType);
            if (p == null || p.isBlank()) {
                normalized = conservativeNormalize(sanitizedRaw);
                quality = "PARSE_FAILED";
            } else {
                normalized = sanitize(p);
            }
        } catch (Throwable t) {
            normalized = conservativeNormalize(sanitizedRaw);
            quality = "PARSE_FAILED";
        }
        normalized = normalized.trim();
        if (normalized.isEmpty()) {
            normalized = sha256(sanitizedRaw).substring(0, 16);
            quality = "PARSE_FAILED";
        }
        String fp = sha256(normalized);
        String risk = buildRiskFlags(normalized, quality);
        return new NormalizedResult(normalized, fp, PARSER_VERSION, quality, risk, truncate(sanitizedRaw, 2000));
    }

    /**
     * Redis/Tair 命令模板归一化（docs/07 §4.4：保留命令名、参数个数，不保存 value）。
     */
    private NormalizedResult normalizeRedisCommand(String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return new NormalizedResult("", sha256(""), PARSER_VERSION, "COMPLETE", "{}", "");
        }
        String cmd = parts[0].toUpperCase();
        int argc = parts.length - 1;
        String normalized = cmd + " [argc=" + argc + "]";
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("redisCommandTemplate", true);
        risk.put("argc", argc);
        return new NormalizedResult(normalized, sha256(normalized), PARSER_VERSION, "COMPLETE",
            toJson(risk), truncate(sanitize(raw), 2000));
    }

    /**
     * 解析失败时的保守归一化（去注释/单引号字符串与数字字面量占位/空白折叠，仍做敏感清理）。
     */
    private String conservativeNormalize(String sql) {
        String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        s = LINE_COMMENT.matcher(s).replaceAll(" ");
        s = s.replaceAll("'(''|[^'])*'", "?");
        s = s.replaceAll("\\b\\d+\\b", "?");
        s = s.replaceAll("\\s+", " ").trim();
        return sanitize(s);
    }

    /**
     * 敏感字面量清理（密码赋值/Bearer/身份证/邮箱/手机）。
     */
    private String sanitize(String s) {
        if (s == null) return null;
        String r = SECRET_LIT.matcher(s).replaceAll("$1=?");
        r = BEARER.matcher(r).replaceAll("Bearer ?");
        r = ID_CARD.matcher(r).replaceAll(MASK);
        r = EMAIL.matcher(r).replaceAll(MASK);
        r = PHONE.matcher(r).replaceAll(MASK);
        return r;
    }

    private String buildRiskFlags(String normalized, String quality) {
        Map<String, Object> risk = new LinkedHashMap<>();
        if ("PARSE_FAILED".equals(quality)) {
            risk.put("parseFailed", true);
        }
        Matcher inM = IN_LIST.matcher(normalized);
        int maxIn = 0;
        int inCount = 0;
        while (inM.find()) {
            inCount++;
            String inside = inM.group(1).trim();
            int n = inside.isEmpty() ? 0 : inside.split(",").length;
            if (n > maxIn) maxIn = n;
        }
        if (inCount > 0) {
            risk.put("inList", true);
            risk.put("inListMaxElements", maxIn);
        }
        String upper = normalized.toUpperCase();
        boolean hasFrom = upper.contains(" FROM ");
        boolean hasWhere = upper.contains(" WHERE ");
        boolean hasLimit = upper.contains(" LIMIT ");
        if (hasFrom && !hasWhere) {
            risk.put("noWhereClause", true);
            if (!hasLimit) {
                risk.put("fullTableScanSuspected", true);
            }
        }
        risk.put("percentileMethod", "max-approx");
        return toJson(risk);
    }

    private static String toJson(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String) {
                sb.append("\"").append(v).append("\"");
            } else {
                sb.append(v);
            }
        }
        return sb.append("}").toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }

    private static String safeVersion() {
        try {
            return String.valueOf(VERSION.getVersionNumber());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * 归一化结果。
     *
     * @param normalizedStatement 归一化模板（默认展示）
     * @param fingerprint          portableFingerprint（SHA-256）
     * @param parserVersion       解析器版本
     * @param ingestQuality       COMPLETE/PARSE_FAILED
     * @param riskFlags           风险标记（JSON）
     * @param sanitizedSample     脱敏样例（原 SQL 敏感清理后）
     */
    public record NormalizedResult(
        String normalizedStatement,
        String fingerprint,
        String parserVersion,
        String ingestQuality,
        String riskFlags,
        String sanitizedSample
    ) {
    }
}
