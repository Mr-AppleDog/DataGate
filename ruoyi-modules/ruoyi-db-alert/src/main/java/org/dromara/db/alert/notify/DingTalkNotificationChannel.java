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
import java.util.Base64;
import java.util.Map;

/**
 * 钉钉机器人通知通道（docs/07 §9）。
 *
 * 钉钉 webhook（HTTPS），markdown 消息；可选加签（HMAC-SHA256(secret, timestamp+换行+secret) → base64）。
 * 通道密钥经 SecretValue 短时使用，不进日志/异常。
 *
 * @author DataGate
 */
@Component
public class DingTalkNotificationChannel implements NotificationChannel {

    private static final ObjectMapper OM = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public String type() {
        return "DINGTALK";
    }

    @Override
    public DeliveryResult send(DbNotificationChannel channel, NotificationMessage message, SecretValue secret) throws Exception {
        Map<String, Object> config = parseConfig(channel.getConfig());
        String url = String.valueOf(config.getOrDefault("url", ""));
        if (url.isBlank() || !url.startsWith("https://")) {
            return DeliveryResult.deadError("400", "钉钉 webhook 必须 HTTPS");
        }
        long timestamp = System.currentTimeMillis();
        String finalUrl = url;
        if (secret != null) {
            String sign = dingSign(secret, timestamp);
            finalUrl = url + (url.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
        }
        String payload = OM.writeValueAsString(Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("title", message.title(), "text", message.body())));
        HttpRequest req = HttpRequest.newBuilder(URI.create(finalUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        String body = resp.body();
        String summary = truncate(body, 500);
        boolean ok = code >= 200 && code < 300 && dingErrCodeIsZero(body);
        if (ok) {
            return DeliveryResult.ok(String.valueOf(code), summary);
        }
        if (code == 429 || code >= 500) {
            return DeliveryResult.retryableError(String.valueOf(code), summary);
        }
        return DeliveryResult.deadError(String.valueOf(code), summary);
    }

    private boolean dingErrCodeIsZero(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> rj = OM.readValue(body, new TypeReference<Map<String, Object>>() {});
            Object ec = rj.get("errcode");
            return ec instanceof Number && ((Number) ec).intValue() == 0;
        } catch (Exception e) {
            return false;
        }
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

    private String dingSign(SecretValue secret, long timestamp) {
        final String[] holder = new String[1];
        secret.useSecret(chars -> {
            try {
                String key = new String(chars);
                String stringToSign = timestamp + "\n" + key;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
                holder[0] = Base64.getEncoder().encodeToString(signData);
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
