package org.dromara.db.alert.notify;

import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.core.security.SecretValue;

/**
 * 通知通道 SPI（docs/07 §9）。
 *
 * <p>P0 实现：DINGTALK（钉钉机器人）/SMTP（邮件）/WEBHOOK（通用 HTTPS）。
 * P1 经同一 SPI 扩展企业微信与飞书。通道密钥经 SecretValue 短时使用，不进日志/异常。</p>
 *
 * <p>Webhook 必须 HTTPS + 签名 + 时间戳 + 防重放 + 域名/IP 白名单（docs/07 §9.2）。</p>
 *
 * @author DataGate
 */
public interface NotificationChannel {

    /**
     * 通道类型（与 dbg_notification_channel.type 对应）
     */
    String type();

    /**
     * 发送一条通知。
     *
     * @param channel 通道配置（非秘密 config + secret_reference）
     * @param message 脱敏渲染消息
     * @param secret  通道秘密（webhook 签名密钥/SMTP 密码），使用后由调用方销毁
     */
    DeliveryResult send(DbNotificationChannel channel, NotificationMessage message, SecretValue secret) throws Exception;
}
