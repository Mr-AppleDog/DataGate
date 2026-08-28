package org.dromara.db.executor.support;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DataGate 业务指标埋点（docs/09 §9.1，M6-02a）。
 *
 * <p>封装 Micrometer 计时器/计数器/活跃度，null-safe（无 actuator/meterRegistry 时 no-op 不阻塞业务）。
 * 命名前缀 datagate.*；标签用 status/engine/action，不含用户明文/SQL 正文（docs/09 §11）。</p>
 *
 * @author DataGate
 */
@Component
public class DatagateMetrics {

    private final MeterRegistry registry;
    private final AtomicLong activeQueries = new AtomicLong();
    private final AtomicLong activeExports = new AtomicLong();
    private final AtomicLong activeChanges = new AtomicLong();

    public DatagateMetrics(Optional<MeterRegistry> registry) {
        this.registry = registry.orElse(null);
        if (this.registry != null) {
            this.registry.gauge("datagate.query.active", activeQueries, AtomicLong::doubleValue);
            this.registry.gauge("datagate.export.active", activeExports, AtomicLong::doubleValue);
            this.registry.gauge("datagate.change.active", activeChanges, AtomicLong::doubleValue);
        }
    }

    /** 计时开始（system clock，无 registry 也安全） */
    public Timer.Sample start() {
        return Timer.start();
    }

    /** 计时结束并记录（name + tagKeyValues 形如 "status","SUCCEEDED"） */
    public void stop(Timer.Sample sample, String name, String... tagKeyValues) {
        if (registry != null && sample != null) {
            sample.stop(Timer.builder(name).tags(Tags.of(tagKeyValues)).register(registry));
        }
    }

    public void increment(String name, String... tagKeyValues) {
        if (registry != null) {
            registry.counter(name, tagKeyValues).increment();
        }
    }

    public void queryStarted() { activeQueries.incrementAndGet(); }
    public void queryEnded() { activeQueries.decrementAndGet(); }
    public void exportStarted() { activeExports.incrementAndGet(); }
    public void exportEnded() { activeExports.decrementAndGet(); }
    public void changeStarted() { activeChanges.incrementAndGet(); }
    public void changeEnded() { activeChanges.decrementAndGet(); }

    /** 是否有活动执行（优雅停机排空判断） */
    public boolean anyActive() {
        return activeQueries.get() > 0 || activeExports.get() > 0 || activeChanges.get() > 0;
    }
}
