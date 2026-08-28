package org.dromara.db.alert.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.core.security.SecretValue;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 通用 HTTPS Webhook 通知通道（docs/07 §9.2）。
 *
 * 安全：必须 HTTPS；HMAC-SHA256 签名（secret, timestamp+body）；时间戳头供接收方防重放；
 * 目标域名/IP 白名单校验。通道密钥经 SecretValue 短时使用，不进日志/异常。
 *
 * @author DataGate
 */
@Component
public class WebhookNotificationChannel implements NotificationChannel {

    private static final ObjectMapper OM = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public String type() {
        return "WEBHOOK";
    }

    @Override
    public DeliveryResult send(DbNotificationChannel channel, NotificationMessage message, SecretValue secret) throws Exception {
        Map<String, Object> config = parseConfig(channel.getConfig());
        String url = String.valueOf(config.getOrDefault("url", ""));
        if (url.isBlank()) {
            return DeliveryResult.deadError("400", "webhook url 未配置");
        }
        if (!url.startsWith("https://")) {
            return DeliveryResult.deadError("400", "webhook 必须 HTTPS");
        }
        if (!isHostAllowed(url, config.get("allowedDomains"))) {
            return DeliveryResult.deadError("403", "webhook 目标域名未在白名单");
        }
        String body = message.body();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = (secret == null) ? "" : sign(secret, timestamp + body);

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-Datagate-Timestamp", timestamp)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10));
        if (!signature.isEmpty()) {
            rb.header("X-Datagate-Signature", signature);
        }
        HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        String summary = truncate(resp.body(), 500);
        if (code >= 200 && code < 300) {
            return DeliveryResult.ok(String.valueOf(code), summary);
        }
        if (code == 429 || code >= 500) {
            return DeliveryResult.retryableError(String.valueOf(code), summary);
        }
        return DeliveryResult.deadError(String.valueOf(code), summary);
    }

    @SuppressWarnings("unchecked")
    private boolean isHostAllowed(String url, Object allowed) {
        if (!(allowed instanceof List) || ((List<?>) allowed).isEmpty()) {
            return true; // 未配置白名单时不阻断（生产建议必配）
        }
        String host = URI.create(url).getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        for (Object d : (List<Object>) allowed) {
            String dm = String.valueOf(d).toLowerCase();
            if (host.equals(dm) || host.endsWith("." + dm)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OM.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String sign(SecretValue secret, String data) {
        final String[] holder = new String[1];
        secret.useSecret(chars -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(new String(chars).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(h.length * 2);
                for (byte b : h) sb.append(String.format("%02x", b));
                holder[0] = sb.toString();
            } catch (Exception e) {
                holder[0] = "";
            }
        });
        return holder[0];
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
