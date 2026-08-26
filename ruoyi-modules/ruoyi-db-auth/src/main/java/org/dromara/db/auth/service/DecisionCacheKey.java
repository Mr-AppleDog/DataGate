package org.dromara.db.auth.service;

import org.dromara.db.core.enums.DbAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 授权判定缓存键（docs/03 第 8 节，AUTH）。
 *
 * <p>键 = actorId + resourceId + action + contextHash + policyVersion。
 * 策略版本变更即令旧缓存不命中；禁止缓存包含密码或完整 SQL。</p>
 *
 * @author DataGate
 */
public final class DecisionCacheKey {

    private DecisionCacheKey() {
    }

    /**
     * 构造缓存键。
     */
    public static String build(Long actorId, Long resourceId, DbAction action,
                               String contextHash, long policyVersion) {
        return "authz:" + actorId + ":" + resourceId + ":" + (action == null ? "?" : action.name())
            + ":" + contextHash + ":v" + policyVersion;
    }

    /**
     * 计算请求上下文的稳定哈希（按 key 排序后 sha256 十六进制）。
     */
    public static String contextHash(Map<String, Object> requestContext) {
        if (requestContext == null || requestContext.isEmpty()) {
            return "0";
        }
        TreeMap<String, Object> sorted = new TreeMap<>(requestContext);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (Exception e) {
            // sha256 不可用：退化为字符串哈希（仍随内容变化）
            return Integer.toHexString(sb.toString().hashCode());
        }
    }
}
