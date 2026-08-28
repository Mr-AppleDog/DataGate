package org.dromara.dromara.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * readiness 探针（docs/09 §11，M6-01b）。
 *
 * <p>节点排空（draining）时返回 OUT_OF_SERVICE，负载均衡不再转发新请求。
 * 正常时 UP。</p>
 *
 * @author DataGate
 */
@Component("drainReadiness")
@RequiredArgsConstructor
public class DrainHealthIndicator implements HealthIndicator {

    private final DrainState drainState;

    @Override
    public Health health() {
        if (drainState.isDraining()) {
            return Health.outOfService().withDetail("draining", true).build();
        }
        return Health.up().withDetail("draining", false).build();
    }
}
