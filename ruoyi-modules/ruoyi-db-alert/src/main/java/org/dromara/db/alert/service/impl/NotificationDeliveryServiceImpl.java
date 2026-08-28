package org.dromara.db.alert.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.alert.domain.DbNotificationDelivery;
import org.dromara.db.alert.mapper.DbAlertEventMapper;
import org.dromara.db.alert.mapper.DbAlertRuleMapper;
import org.dromara.db.alert.mapper.DbNotificationChannelMapper;
import org.dromara.db.alert.mapper.DbNotificationDeliveryMapper;
import org.dromara.db.alert.notify.DeliveryResult;
import org.dromara.db.alert.notify.MessageRenderer;
import org.dromara.db.alert.notify.NotificationChannel;
import org.dromara.db.alert.notify.NotificationMessage;
import org.dromara.db.alert.notify.NotificationRetryPolicy;
import org.dromara.db.alert.notify.NotificationRetryPolicy.Outcome;
import org.dromara.db.alert.service.INotificationDeliveryService;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知投递实现（docs/07 §9.2）。
 *
 * 入队：渲染脱敏消息 → 存哈希 → outbox PENDING。
 * 派发：解析通道 + 秘密 → 发送 → 重试策略定 SENT/FAILED/DEAD + 指数退避 next_retry_at。
 * 通道密钥经 SecretValue try-with-resources 短时使用，不进日志/异常。
 *
 * @author DataGate
 */
@Service
public class NotificationDeliveryServiceImpl implements INotificationDeliveryService {

    private final DbNotificationDeliveryMapper deliveryMapper;
    private final DbNotificationChannelMapper channelMapper;
    private final DbAlertEventMapper eventMapper;
    private final DbAlertRuleMapper ruleMapper;
    private final MessageRenderer renderer;
    private final ICredentialVaultService credentialVaultService;
    private final Map<String, NotificationChannel> channelsByType;

    public NotificationDeliveryServiceImpl(DbNotificationDeliveryMapper deliveryMapper,
                                           DbNotificationChannelMapper channelMapper,
                                           DbAlertEventMapper eventMapper,
                                           DbAlertRuleMapper ruleMapper,
                                           MessageRenderer renderer,
                                           ICredentialVaultService credentialVaultService,
                                           List<NotificationChannel> channels) {
        this.deliveryMapper = deliveryMapper;
        this.channelMapper = channelMapper;
        this.eventMapper = eventMapper;
        this.ruleMapper = ruleMapper;
        this.renderer = renderer;
        this.credentialVaultService = credentialVaultService;
        Map<String, NotificationChannel> m = new LinkedHashMap<>();
        for (NotificationChannel c : channels) {
            m.put(c.type(), c);
        }
        this.channelsByType = m;
    }

    @Override
    public void enqueue(DbAlertEvent event, DbAlertRule rule, List<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) {
            return;
        }
        NotificationMessage message = renderer.render(event, rule);
        String bodyHash = sha256(message.body());
        Date now = new Date();
        for (Long channelId : channelIds) {
            DbNotificationDelivery d = new DbNotificationDelivery();
            d.setEventId(event.getId());
            d.setChannelId(channelId);
            d.setTemplateVersion(renderer.templateVersion());
            d.setTargetSummary(maskTarget(channelId));
            d.setStatus("PENDING");
            d.setAttemptCount(0);
            d.setNextRetryAt(now);
            d.setRenderedBodyHash(bodyHash);
            d.setCreatedAt(now);
            deliveryMapper.insert(d);
        }
    }

    @Override
    public int dispatchPending(int batchSize) {
        Date now = new Date();
        List<DbNotificationDelivery> pendings = deliveryMapper.selectList(new LambdaQueryWrapper<DbNotificationDelivery>()
            .in(DbNotificationDelivery::getStatus, List.of("PENDING", "FAILED"))
            .le(DbNotificationDelivery::getNextRetryAt, now)
            .lt(DbNotificationDelivery::getAttemptCount, NotificationRetryPolicy.MAX_ATTEMPTS)
            .orderByAsc(DbNotificationDelivery::getNextRetryAt)
            .last("limit " + Math.max(1, Math.min(batchSize, 200))));
        int processed = 0;
        for (DbNotificationDelivery d : pendings) {
            processOne(d, now);
            processed++;
        }
        return processed;
    }

    private void processOne(DbNotificationDelivery d, Date now) {
        DbAlertEvent event = eventMapper.selectById(d.getEventId());
        if (event == null) {
            markDead(d, now, "404", "事件不存在");
            return;
        }
        DbAlertRule rule = ruleMapper.selectById(event.getRuleId());
        DbNotificationChannel channel = channelMapper.selectById(d.getChannelId());
        if (channel == null) {
            markDead(d, now, "404", "通道不存在");
            return;
        }
        NotificationChannel sender = channelsByType.get(channel.getType());
        if (sender == null) {
            markDead(d, now, "400", "未知通道类型: " + channel.getType());
            return;
        }
        NotificationMessage message = renderer.render(event, rule);
        DeliveryResult result;
        if (channel.getSecretReference() != null && !channel.getSecretReference().isBlank()) {
            try (SecretValue secret = resolveSecret(channel.getSecretReference())) {
                result = sendSafely(sender, channel, message, secret);
            } catch (Exception e) {
                result = DeliveryResult.retryableError("500", truncate(safeMsg(e), 500));
            }
        } else {
            result = sendSafely(sender, channel, message, null);
        }
        applyOutcome(d, result, now);
    }

    private DeliveryResult sendSafely(NotificationChannel sender, DbNotificationChannel channel,
                                      NotificationMessage message, SecretValue secret) {
        try {
            return sender.send(channel, message, secret);
        } catch (Exception e) {
            return DeliveryResult.retryableError("500", truncate(safeMsg(e), 500));
        }
    }

    private void applyOutcome(DbNotificationDelivery d, DeliveryResult result, Date now) {
        Outcome o = NotificationRetryPolicy.onResult(
            d.getAttemptCount() == null ? 0 : d.getAttemptCount(), result, now);
        DbNotificationDelivery update = new DbNotificationDelivery();
        update.setId(d.getId());
        update.setStatus(o.status());
        update.setAttemptCount(o.attemptCount());
        update.setNextRetryAt(o.nextRetryAt());
        update.setResponseCode(result.responseCode());
        update.setResponseSummary(truncate(result.responseSummary(), 500));
        if ("SENT".equals(o.status()) || "DEAD".equals(o.status())) {
            update.setCompletedAt(now);
        }
        deliveryMapper.updateById(update);
    }

    private void markDead(DbNotificationDelivery d, Date now, String code, String summary) {
        DbNotificationDelivery update = new DbNotificationDelivery();
        update.setId(d.getId());
        update.setStatus("DEAD");
        update.setAttemptCount((d.getAttemptCount() == null ? 0 : d.getAttemptCount()) + 1);
        update.setResponseCode(code);
        update.setResponseSummary(truncate(summary, 500));
        update.setCompletedAt(now);
        deliveryMapper.updateById(update);
    }

    private SecretValue resolveSecret(String secretReference) {
        try {
            return credentialVaultService.resolveActiveSecret(Long.parseLong(secretReference.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String maskTarget(Long channelId) {
        return "channel:" + channelId;
    }

    private static String safeMsg(Exception e) {
        String s = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return s;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-unavailable";
        }
    }
}
