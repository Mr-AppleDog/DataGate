package org.dromara.db.alert.notify;

import org.dromara.db.alert.domain.DbNotificationChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Webhook 通道安全校验测试（docs/07 §9.2：HTTPS 强制 + 域名白名单，配置错误进 DEAD）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("Webhook 通道安全校验")
class WebhookNotificationChannelTest {

    private final WebhookNotificationChannel channel = new WebhookNotificationChannel(() -> {
        throw new AssertionError("校验失败时不应初始化 HTTP 客户端");
    });

    private DbNotificationChannel ch(String config) {
        DbNotificationChannel c = new DbNotificationChannel();
        c.setConfig(config);
        return c;
    }

    private NotificationMessage msg() {
        return new NotificationMessage("P1", "title", "body", "/link");
    }

    @Test
    @DisplayName("url 未配置→DEAD")
    void missingUrlDead() throws Exception {
        var r = channel.send(ch("{}"), msg(), null);
        assertFalse(r.success());
        assertFalse(r.retryable());
    }

    @Test
    @DisplayName("非 HTTPS→DEAD")
    void nonHttpsDead() throws Exception {
        var r = channel.send(ch("{\"url\":\"http://hook.example.com/x\",\"allowedDomains\":[\"example.com\"]}"), msg(), null);
        assertFalse(r.success());
        assertFalse(r.retryable());
    }

    @Test
    @DisplayName("目标域名不在白名单→DEAD 403")
    void hostNotInWhitelistDead() throws Exception {
        var r = channel.send(ch("{\"url\":\"https://evil.com/x\",\"allowedDomains\":[\"example.com\"]}"), msg(), null);
        assertFalse(r.success());
        assertFalse(r.retryable());
        assertEquals("403", r.responseCode());
    }
}
