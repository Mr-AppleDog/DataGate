package org.dromara.db.alert.notify;

/**
 * 通知投递结果（docs/07 §9.2）。
 *
 * @param success          是否成功
 * @param responseCode     HTTP/SMTP 响应码
 * @param responseSummary  响应摘要（脱敏后，不含秘密）
 * @param retryable        是否可重试（429/5xx=true，4xx 配置错误=false 进 DEAD）
 *
 * @author DataGate
 */
public record DeliveryResult(boolean success, String responseCode, String responseSummary, boolean retryable) {

    public static DeliveryResult ok(String code, String summary) {
        return new DeliveryResult(true, code, summary, false);
    }

    public static DeliveryResult retryableError(String code, String summary) {
        return new DeliveryResult(false, code, summary, true);
    }

    public static DeliveryResult deadError(String code, String summary) {
        return new DeliveryResult(false, code, summary, false);
    }
}
