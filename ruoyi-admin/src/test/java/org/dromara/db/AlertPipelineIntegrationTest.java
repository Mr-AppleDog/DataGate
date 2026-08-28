package org.dromara.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.DromaraApplication;
import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.mapper.DbAlertEventMapper;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.dromara.db.core.spi.MetricEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 告警端到端集成测试（docs/07 §2 数据流：指标事件→规则评估→告警事件）。
 * 发布 SlowMetricEvent → AlertEvaluationService 匹配默认规则 → 验证 DbAlertEvent 创建。
 * 仅 -DtestTags=integration 触发；需元库（dev profile，V12 已迁移）。
 *
 * @author DataGate
 */
@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(classes = DromaraApplication.class)
@DisplayName("告警端到端：指标发布→告警事件")
class AlertPipelineIntegrationTest {

    @Autowired
    private MetricEventPublisher publisher;

    @Autowired
    private DbAlertEventMapper eventMapper;

    @Test
    @DisplayName("P1 单次慢查询 ≥30s 触发默认规则 9101 创建 FIRING 事件")
    void publishMetricCreatesP1Event() {
        // pre-cleanup：清理可能残留的同源事件，避免去重命中遗留导致触发计数断言失败
        eventMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DbAlertEvent>()
            .eq(DbAlertEvent::getDataSourceId, 999L)
            .eq(DbAlertEvent::getFingerprint, "fp-e2e-test"));
        long testDs = 999L;
        long windowEnd = System.currentTimeMillis();
        SlowMetricEvent metric = new SlowMetricEvent(
            testDs, null, null, "fp-e2e-test", "SELECT * FROM orders WHERE id = ?",
            "MYSQL", "db1", "prod",
            windowEnd - 60000L, windowEnd,
            5, 0, 180000000L, 35000000L, 35000000L,
            null, null, null, false, 0, false);
        publisher.publish(metric);

        DbAlertEvent ev = eventMapper.selectOne(new LambdaQueryWrapper<DbAlertEvent>()
            .eq(DbAlertEvent::getDataSourceId, testDs)
            .eq(DbAlertEvent::getFingerprint, "fp-e2e-test")
            .ne(DbAlertEvent::getStatus, "RESOLVED")
            .last("limit 1"));
        assertNotNull(ev, "应创建 P1 告警事件（规则 9101 匹配）");
        assertEquals("P1", ev.getSeverity());
        assertEquals("FIRING", ev.getStatus());
        assertEquals(1, ev.getTriggerCount());
        // cleanup，避免污染元库
        eventMapper.deleteById(ev.getId());
    }
}
