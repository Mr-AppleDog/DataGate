package org.dromara.db.core.spi;

import org.dromara.db.core.domain.SlowMetricEvent;

/**
 * 指标事件发布者 SPI（docs/02 §6.8：observability 向告警模块发布规范化指标事件）。
 *
 * <p>observability 在桶聚合/采集后调用本 SPI 发布 {@link SlowMetricEvent}；
 * alert 模块实现本接口执行规则评估、去重抑制与通知入队。observability 不直接依赖 alert，
 * 仅依赖本 SPI（db-core），由 Spring 注入 alert 实现（无实现时 no-op 不阻塞采集）。</p>
 *
 * @author DataGate
 */
public interface MetricEventPublisher {

    /**
     * 发布一条指标事件（异步语义：实现方应快速返回，重活内部异步/入队）。
     */
    void publish(SlowMetricEvent event);
}
