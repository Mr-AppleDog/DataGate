package org.dromara.db.observability.schedule;

import org.dromara.db.observability.service.ISlowQueryCollectionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 慢查询采集调度（docs/07 §4.1：默认每分钟）。
 *
 * 使用 Spring @Scheduled（@EnableScheduling 由 ruoyi-job SnailJobConfig 启用）。
 * 集群安全：collectOne 内部 Redisson 单源锁防止多节点重复拉取同一来源（docs/07 §4.1）。
 * 偏差：docs/02 列 SnailJob 用于慢查询采集；此处用 @Scheduled + 分布式单源锁实现等价能力
 *（更简单、集群安全），集中式调度可在 M6 切换为 SnailJob 任务（collectAll 幂等，切换无影响）。
 *
 * @author DataGate
 */
@Component
public class SlowQueryScheduler {

    private final ISlowQueryCollectionService collectionService;

    public SlowQueryScheduler(ISlowQueryCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Scheduled(fixedDelayString = "${datagate.observability.slow-collect-interval-ms:60000}", initialDelay = 30000)
    public void collectAll() {
        collectionService.collectAll();
    }
}
