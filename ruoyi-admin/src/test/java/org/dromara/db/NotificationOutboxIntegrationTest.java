package org.dromara.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.DromaraApplication;
import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.alert.domain.DbNotificationChannel;
import org.dromara.db.alert.domain.DbNotificationDelivery;
import org.dromara.db.alert.mapper.DbAlertEventMapper;
import org.dromara.db.alert.mapper.DbAlertRuleMapper;
import org.dromara.db.alert.mapper.DbNotificationChannelMapper;
import org.dromara.db.alert.mapper.DbNotificationDeliveryMapper;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.dromara.db.core.spi.MetricEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 通知 outbox 端到端测试（docs/07 §9.2：告警事件→通知入队 outbox）。
 * 仅 -DtestTags=integration 触发。
 *
 * @author DataGate
 */
@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(classes = DromaraApplication.class)
@DisplayName("告警→通知 outbox 入队")
class NotificationOutboxIntegrationTest {

    private static final long TEST_DS = 998L;
    private static final String TEST_FP = "fp-outbox-test";
    private static final long TEST_RULE_ID = 9901L;
    private static final long TEST_CHANNEL_ID = 9901L;

    @Autowired private MetricEventPublisher publisher;
    @Autowired private DbAlertRuleMapper ruleMapper;
    @Autowired private DbNotificationChannelMapper channelMapper;
    @Autowired private DbAlertEventMapper eventMapper;
    @Autowired private DbNotificationDeliveryMapper deliveryMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private void preCleanup() {
        DbAlertEvent ev = eventMapper.selectOne(new LambdaQueryWrapper<DbAlertEvent>()
            .eq(DbAlertEvent::getDataSourceId, TEST_DS).eq(DbAlertEvent::getFingerprint, TEST_FP).last("limit 1"));
        if (ev != null) {
            jdbcTemplate.update("DELETE FROM dbg_notification_delivery WHERE event_id = ?", ev.getId());
            jdbcTemplate.update("DELETE FROM dbg_alert_event WHERE id = ?", ev.getId());
        }
        // channel/rule 带 @TableLogic，deleteById 为逻辑删除（行仍存在致 PK 冲突），故物理删除
        jdbcTemplate.update("DELETE FROM dbg_alert_rule WHERE id = ?", TEST_RULE_ID);
        jdbcTemplate.update("DELETE FROM dbg_notification_channel WHERE id = ?", TEST_CHANNEL_ID);
    }

    @Test
    @DisplayName("指标发布→规则匹配→告警事件+outbox 投递入队")
    void metricCreatesEventAndOutboxDelivery() {
        preCleanup();
        DbNotificationChannel channel = new DbNotificationChannel();
        channel.setId(TEST_CHANNEL_ID);
        channel.setType("WEBHOOK");
        channel.setName("测试通道");
        channel.setConfig("{\"url\":\"https://nonexistent.example.datagate/x\",\"allowedDomains\":[\"datagate\"]}");
        channel.setStatus("ACTIVE");
        channel.setCreateTime(new Date());
        channelMapper.insert(channel);

        DbAlertRule rule = new DbAlertRule();
        rule.setId(TEST_RULE_ID);
        rule.setName("测试规则 outbox");
        rule.setSeverity("P3");
        rule.setScope("{}");
        rule.setMetric("SINGLE_MAX_DURATION");
        rule.setOperator("GE");
        rule.setThreshold(new BigDecimal("1"));
        rule.setDurationSeconds(0);
        rule.setFirstSeenOnly("0");
        rule.setDedupWindowSeconds(900);
        rule.setRouting("{\"channels\":[" + TEST_CHANNEL_ID + "]}");
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setCreateTime(new Date());
        ruleMapper.insert(rule);

        try {
            long windowEnd = System.currentTimeMillis();
            SlowMetricEvent metric = new SlowMetricEvent(
                TEST_DS, null, null, TEST_FP, "SELECT * FROM t WHERE id = ?",
                "MYSQL", "db1", "prod",
                windowEnd - 60000L, windowEnd,
                3, 0, 3000000L, 1000000L, 1000000L,
                null, null, null, false, 0, false);
            publisher.publish(metric);

            DbAlertEvent ev = eventMapper.selectOne(new LambdaQueryWrapper<DbAlertEvent>()
                .eq(DbAlertEvent::getDataSourceId, TEST_DS)
                .eq(DbAlertEvent::getFingerprint, TEST_FP)
                .ne(DbAlertEvent::getStatus, "RESOLVED")
                .last("limit 1"));
            assertNotNull(ev, "应创建告警事件");
            assertEquals("FIRING", ev.getStatus());
            assertEquals("P3", ev.getSeverity());

            // 验证 outbox 投递已入队（状态可能被后台调度器派发，故只断言存在）
            DbNotificationDelivery del = deliveryMapper.selectOne(new LambdaQueryWrapper<DbNotificationDelivery>()
                .eq(DbNotificationDelivery::getEventId, ev.getId())
                .eq(DbNotificationDelivery::getChannelId, TEST_CHANNEL_ID)
                .last("limit 1"));
            assertNotNull(del, "应入队 outbox 投递");
        } finally {
            preCleanup();
        }
    }
}
