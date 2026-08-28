package org.dromara.db.alert.service;

import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.alert.notify.DeliveryResult;

public interface INotificationChannelService {
    java.util.List<DbNotificationChannel> list();
    DbNotificationChannel create(DbNotificationChannel channel);
    DbNotificationChannel update(DbNotificationChannel channel);
    DeliveryResult test(Long channelId);
}
