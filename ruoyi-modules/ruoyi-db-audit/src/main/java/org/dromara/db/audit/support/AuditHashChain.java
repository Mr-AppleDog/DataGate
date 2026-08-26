package org.dromara.db.audit.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计哈希链计算（纯函数，docs/08 第 9.3 节）。
 *
 * <p>eventHash = SHA-256( canonical(previousHash | event fields) )。
 * 哈希输入覆盖事件全部不可变事实字段，篡改任一字段都会导致链校验失败。</p>
 *
 * @author DataGate
 */
public final class AuditHashChain {

    /**
     * 分片首事件的 genesis 前驱
     */
    public static final String GENESIS = "GENESIS";

    /**
     * 哈希链分片键：UTC 日
     */
    private static final DateTimeFormatter CHAIN_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AuditHashChain() {
    }

    /**
     * 计算分片键（UTC 日，yyyyMMdd）
     */
    public static String chainKeyOf(Instant occurredAt) {
        return CHAIN_KEY_FORMAT.format(occurredAt);
    }

    /**
     * 计算事件哈希。字段顺序固定；Map 按键名排序保证规范序列化。
     */
    public static String computeEventHash(
        String eventId, String category, String action,
        Long actorId, Map<String, Object> actorSnapshot,
        String targetType, String targetId, Map<String, Object> targetSnapshot,
        String result, String sourceIp, String traceId,
        Map<String, Object> details, Instant occurredAt, String previousHash) {
        String canonical = String.join("|",
            nullToEmpty(previousHash),
            nullToEmpty(eventId),
            nullToEmpty(category),
            nullToEmpty(action),
            actorId == null ? "" : actorId.toString(),
            canonicalize(actorSnapshot),
            nullToEmpty(targetType),
            nullToEmpty(targetId),
            canonicalize(targetSnapshot),
            nullToEmpty(result),
            nullToEmpty(sourceIp),
            nullToEmpty(traceId),
            canonicalize(details),
            occurredAt == null ? "" : occurredAt.toString()
        );
        return sha256Hex(canonical);
    }

    /**
     * Map 规范序列化：按键名排序，嵌套结构递归处理
     */
    @SuppressWarnings("unchecked")
    private static String canonicalize(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : new TreeMap<>(map).entrySet()) {
            sb.append(e.getKey()).append('=').append(canonicalizeValue(e.getValue())).append(';');
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String canonicalizeValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> m) {
            return canonicalize((Map<String, Object>) m);
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : list) {
                sb.append(canonicalizeValue(o)).append(',');
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * SHA-256 十六进制
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
