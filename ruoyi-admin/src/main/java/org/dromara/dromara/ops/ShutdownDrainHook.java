package org.dromara.dromara.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.executor.support.DatagateMetrics;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 优雅停机排空钩子（docs/09 §11，M6-01b）。
 *
 * <p>ContextClosed 时先置 draining=true（readiness 失败，不再接收新请求），
 * 再轮询活动查询/导出/变更直至排空或达 ~55s（留余量给连接池关闭），失败关闭不强制中断。
 * Spring graceful shutdown（server.shutdown=graceful, 60s）保证 HTTP in-flight 完成。</p>
 *
 * @author DataGate
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShutdownDrainHook implements ApplicationListener<ContextClosedEvent> {

    private static final long DRAIN_BUDGET_MS = 55_000L;
    private static final long POLL_INTERVAL_MS = 500L;

    private final DrainState drainState;
    private final DatagateMetrics metrics;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        drainState.setDraining(true);
        log.info("优雅停机：开始排空活动查询/导出/变更（最多 {}ms）", DRAIN_BUDGET_MS);
        long deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS;
        long waited = 0;
        while (System.currentTimeMillis() < deadline) {
            if (!metrics.anyActive()) {
                break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
                waited += POLL_INTERVAL_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (metrics.anyActive()) {
            log.warn("优雅停机：超时仍有活动执行，继续关闭（不强制中断，由超时/取消兜底）");
        } else {
            log.info("优雅停机：活动执行已排空（等待 {}ms）", waited);
        }
    }
}
