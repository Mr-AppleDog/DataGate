package org.dromara.db.alert.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.alert.mapper.DbNotificationChannelMapper;
import org.dromara.db.alert.notify.DeliveryResult;
import org.dromara.db.alert.notify.MessageRenderer;
import org.dromara.db.alert.notify.NotificationChannel;
import org.dromara.db.alert.notify.NotificationMessage;
import org.dromara.db.alert.service.INotificationChannelService;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NotificationChannelServiceImpl implements INotificationChannelService {
    private final DbNotificationChannelMapper channelMapper;
    private final ICredentialVaultService credentialVaultService;
    private final Map<String, NotificationChannel> channelsByType;

    public NotificationChannelServiceImpl(DbNotificationChannelMapper channelMapper,
                                          ICredentialVaultService credentialVaultService,
                                          List<NotificationChannel> channels) {
        this.channelMapper = channelMapper;
        this.credentialVaultService = credentialVaultService;
        Map<String, NotificationChannel> m = new LinkedHashMap<>();
        for (NotificationChannel c : channels) m.put(c.type(), c);
        this.channelsByType = m;
    }

    public List<DbNotificationChannel> list() {
        return channelMapper.selectList(new LambdaQueryWrapper<DbNotificationChannel>().orderByDesc(DbNotificationChannel::getCreateTime));
    }
    public DbNotificationChannel create(DbNotificationChannel channel) {
        if (channel.getStatus() == null) channel.setStatus("ACTIVE");
        channel.setCreateTime(new Date());
        channelMapper.insert(channel);
        return channel;
    }
    public DbNotificationChannel update(DbNotificationChannel channel) {
        channel.setUpdateTime(new Date());
        int rows = channelMapper.updateById(channel);
        if (rows <= 0) throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "通道版本已变化");
        return channelMapper.selectById(channel.getId());
    }
    public DeliveryResult test(Long channelId) {
        DbNotificationChannel ch = channelMapper.selectById(channelId);
        if (ch == null) throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE, "通道不存在");
        NotificationChannel sender = channelsByType.get(ch.getType());
        if (sender == null) throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, "未知通道类型");
        NotificationMessage msg = new NotificationMessage("P3", "DataGate 通道测试", "这是一条 DataGate 告警通道测试消息。", "/test");
        if (ch.getSecretReference() != null && !ch.getSecretReference().isBlank()) {
            try (SecretValue secret = resolveSecret(ch.getSecretReference())) {
                return sendSafely(sender, ch, msg, secret);
            } catch (Exception e) {
                return DeliveryResult.retryableError("500", e.getClass().getSimpleName());
            }
        }
        return sendSafely(sender, ch, msg, null);
    }
    private DeliveryResult sendSafely(NotificationChannel sender, DbNotificationChannel ch, NotificationMessage msg, SecretValue secret) {
        try { return sender.send(ch, msg, secret); }
        catch (Exception e) { return DeliveryResult.retryableError("500", e.getClass().getSimpleName()); }
    }
    private SecretValue resolveSecret(String ref) {
        try { return credentialVaultService.resolveActiveSecret(Long.parseLong(ref.trim())); }
        catch (NumberFormatException e) { return null; }
    }
}
