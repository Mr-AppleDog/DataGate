package org.dromara.db.alert.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.core.security.SecretValue;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * SMTP 邮件通知通道（docs/07 §9）。
 *
 * 配置 JSON（非秘密）：host/port/from/to/useTLS/username；密码经 SecretValue（secret_reference）短时使用。
 * SMTP 投递失败默认可重试（429/5xx），认证错误进 DEAD。密钥不进日志/异常。
 *
 * @author DataGate
 */
@Component
public class SmtpNotificationChannel implements NotificationChannel {

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String type() {
        return "SMTP";
    }

    @Override
    public DeliveryResult send(DbNotificationChannel channel, NotificationMessage message, SecretValue secret) throws Exception {
        Map<String, Object> config = parseConfig(channel.getConfig());
        String host = String.valueOf(config.getOrDefault("host", ""));
        int port = toInt(config.get("port"), 25);
        String from = String.valueOf(config.getOrDefault("from", "datagate@local"));
        String to = String.valueOf(config.getOrDefault("to", ""));
        boolean useTLS = Boolean.TRUE.equals(config.get("useTLS"));
        String username = config.get("username") == null ? null : String.valueOf(config.get("username"));

        if (host.isBlank() || to.isBlank()) {
            return DeliveryResult.deadError("400", "SMTP host/to 未配置");
        }
        Properties props = new Properties();
        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", String.valueOf(port));
        props.setProperty("mail.smtp.auth", String.valueOf(username != null));
        if (useTLS) {
            props.setProperty("mail.smtp.starttls.enable", "true");
        }
        props.setProperty("mail.smtp.connectiontimeout", "5000");
        props.setProperty("mail.smtp.timeout", "10000");

        Session session;
        if (username != null && secret != null) {
            final String user = username;
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    final String[] pw = new String[1];
                    secret.useSecret(chars -> pw[0] = new String(chars));
                    return new PasswordAuthentication(user, pw[0]);
                }
            });
        } else {
            session = Session.getInstance(props);
        }
        try {
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(from));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
            mime.setSubject(message.title(), StandardCharsets.UTF_8.name());
            mime.setText(message.body(), StandardCharsets.UTF_8.name());
            Transport.send(mime);
            return DeliveryResult.ok("250", "SMTP OK");
        } catch (Exception e) {
            String cls = e.getClass().getSimpleName();
            // 认证/配置错误进 DEAD，临时失败重试
            if (cls.contains("Authentication") || cls.contains("SendFailed")) {
                return DeliveryResult.deadError("550", cls);
            }
            return DeliveryResult.retryableError("450", cls);
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

    private static int toInt(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
