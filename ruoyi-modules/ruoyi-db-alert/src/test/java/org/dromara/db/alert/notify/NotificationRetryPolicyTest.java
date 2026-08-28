package org.dromara.db.alert.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知重试策略测试（docs/07 §9.2：指数退避，最多 8 次，4xx→DEAD，429/5xx 重试）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("通知投递重试策略")
class NotificationRetryPolicyTest {

    private final Date now = new Date(1_700_000_000_000L);

    @Test
    @DisplayName("成功→SENT，attemptCount+1，nextRetry=now")
    void successSent() {
        var o = NotificationRetryPolicy.onResult(0, DeliveryResult.ok("200", "ok"), now);
        assertEquals("SENT", o.status());
        assertEquals(1, o.attemptCount());
        assertEquals(now, o.nextRetryAt());
    }

    @Test
    @DisplayName("可重试错误→FAILED，指数退避 nextRetry")
    void retryableFailed() {
        var o = NotificationRetryPolicy.onResult(2, DeliveryResult.retryableError("429", "rate"), now);
        assertEquals("FAILED", o.status());
        assertEquals(3, o.attemptCount());
        assertTrue(o.nextRetryAt().after(now));
        assertEquals(now.getTime() + NotificationRetryPolicy.backoffMs(3), o.nextRetryAt().getTime());
    }

    @Test
    @DisplayName("达上限可重试→DEAD")
    void retryableAtMaxDead() {
        var o = NotificationRetryPolicy.onResult(7, DeliveryResult.retryableError("500", "err"), now);
        assertEquals("DEAD", o.status());
        assertEquals(8, o.attemptCount());
        assertNull(o.nextRetryAt());
    }

    @Test
    @DisplayName("不可重试（4xx）→DEAD")
    void nonRetryableDead() {
        var o = NotificationRetryPolicy.onResult(1, DeliveryResult.deadError("400", "bad"), now);
        assertEquals("DEAD", o.status());
        assertFalse(o.nextRetryAt() != null);
    }

    @Test
    @DisplayName("指数退避：1,2,4...分钟，封顶 30 分钟")
    void backoffExponential() {
        assertEquals(60_000L, NotificationRetryPolicy.backoffMs(1));
        assertEquals(120_000L, NotificationRetryPolicy.backoffMs(2));
        assertEquals(240_000L, NotificationRetryPolicy.backoffMs(3));
        assertEquals(30 * 60_000L, NotificationRetryPolicy.backoffMs(10));
    }
}
