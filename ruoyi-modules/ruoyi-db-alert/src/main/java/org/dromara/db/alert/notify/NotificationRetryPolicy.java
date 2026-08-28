package org.dromara.db.alert.notify;

import java.util.Date;

/**
 * 通知投递重试策略（docs/07 §9.2：指数退避，最多 8 次；4xx→DEAD，429/5xx 重试）。
 * 纯算法，可单测。
 *
 * @author DataGate
 */
public final class NotificationRetryPolicy {

    public static final int MAX_ATTEMPTS = 8;

    private NotificationRetryPolicy() {
    }

    public record Outcome(String status, Date nextRetryAt, int attemptCount) {
    }

    /**
     * 根据投递结果决定下一次状态。
     *
     * @param currentAttempts 当前已尝试次数（含本次即将计入）
     * @param result          投递结果
     * @param now             当前时间
     */
    public static Outcome onResult(int currentAttempts, DeliveryResult result, Date now) {
        int next = currentAttempts + 1;
        if (result.success()) {
            return new Outcome("SENT", now, next);
        }
        if (!result.retryable() || next >= MAX_ATTEMPTS) {
            return new Outcome("DEAD", null, next);
        }
        long delayMs = backoffMs(next);
        return new Outcome("FAILED", new Date(now.getTime() + delayMs), next);
    }

    /**
     * 指数退避：1,2,4,8... 分钟，封顶 30 分钟。
     */
    public static long backoffMs(int attempt) {
        long minutes = (long) Math.pow(2, attempt - 1);
        minutes = Math.min(minutes, 30);
        return minutes * 60_000L;
    }
}
