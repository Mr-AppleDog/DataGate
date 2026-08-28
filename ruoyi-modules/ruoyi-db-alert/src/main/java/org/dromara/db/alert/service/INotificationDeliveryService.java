package org.dromara.db.alert.service;

import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.domain.DbAlertRule;

import java.util.List;

/**
 * 通知投递服务（docs/07 §9.2：outbox 持久化、指数退避重试、死信）。
 *
 * @author DataGate
 */
public interface INotificationDeliveryService {

    /**
     * 入队待发投递（outbox）。消息正文只存脱敏渲染版本哈希。
     */
    void enqueue(DbAlertEvent event, DbAlertRule rule, List<Long> channelIds);

    /**
     * 派发待发/待重试投递（指数退避重试，4xx→DEAD，429/5xx 重试，最多 8 次）。
     *
     * @param batchSize 单批最大派发数
     * @return 本批处理数
     */
    int dispatchPending(int batchSize);
}
