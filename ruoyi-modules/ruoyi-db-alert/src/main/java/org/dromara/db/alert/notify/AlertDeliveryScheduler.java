package org.dromara.db.alert.notify;

import org.dromara.db.alert.service.INotificationDeliveryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通知投递调度（docs/07 §9.2：每分钟派发 outbox 待发/待重试）。
 * @EnableScheduling 由 ruoyi-job SnailJobConfig 启用。
 *
 * @author DataGate
 */
@Component
public class AlertDeliveryScheduler {

    private final INotificationDeliveryService deliveryService;

    public AlertDeliveryScheduler(INotificationDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    public void dispatch() {
        deliveryService.dispatchPending(50);
    }
}
