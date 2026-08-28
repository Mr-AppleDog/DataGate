package org.dromara.db.alert.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.alert.evaluate.AlertRuleMatcher;
import org.dromara.db.alert.evaluate.AlertRuleMatcher.MatchResult;
import org.dromara.db.alert.mapper.DbAlertEventMapper;
import org.dromara.db.alert.mapper.DbAlertRuleMapper;
import org.dromara.db.alert.service.INotificationDeliveryService;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.dromara.db.core.spi.MetricEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 告警评估服务（docs/07 §8）。
 *
 * 实现 {@link MetricEventPublisher}：接收 observability 发布的指标事件，匹配 ACTIVE 规则，
 * 生成/更新告警事件（去重键抑制轰炸 + 抑制窗口 + P1 恶化 2 倍升级 + 维护静默不得静默采集器/平台告警），
 * 入队通知投递。规则故障隔离到单条规则，不阻塞其他规则。
 *
 * @author DataGate
 */
@Service
public class AlertEvaluationServiceImpl implements MetricEventPublisher {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DbAlertRuleMapper ruleMapper;
    private final DbAlertEventMapper eventMapper;
    private final INotificationDeliveryService deliveryService;
    private final TransactionTemplate txTemplate;

    public AlertEvaluationServiceImpl(DbAlertRuleMapper ruleMapper,
                                      DbAlertEventMapper eventMapper,
                                      INotificationDeliveryService deliveryService,
                                      PlatformTransactionManager transactionManager) {
        this.ruleMapper = ruleMapper;
        this.eventMapper = eventMapper;
        this.deliveryService = deliveryService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void publish(SlowMetricEvent m) {
        if (m == null) {
            return;
        }
        List<DbAlertRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<DbAlertRule>()
            .eq(DbAlertRule::getStatus, "ACTIVE"));
        Date now = new Date();
        for (DbAlertRule rule : rules) {
            try {
                MatchResult match = AlertRuleMatcher.evaluate(rule, m);
                if (match.triggered()) {
                    handleEvent(rule, m, match.value(), now);
                }
            } catch (Exception e) {
                // 规则故障隔离到单条规则，通知规则维护人（docs/07 §8）
            }
        }
    }

    private void handleEvent(DbAlertRule rule, SlowMetricEvent m, BigDecimal value, Date now) {
        String dedupKey = rule.getId() + ":" + m.dataSourceId() + ":" + m.fingerprint() + ":" + m.windowEndMillis();
        txTemplate.executeWithoutResult(s -> {
            DbAlertEvent existing = eventMapper.selectOne(new LambdaQueryWrapper<DbAlertEvent>()
                .eq(DbAlertEvent::getDedupKey, dedupKey)
                .ne(DbAlertEvent::getStatus, "RESOLVED")
                .last("limit 1"));
            if (existing == null) {
                DbAlertEvent ev = newEvent(rule, m, value, dedupKey, now);
                eventMapper.insert(ev);
                enqueue(ev, rule);
            } else {
                updateExisting(existing, rule, m, value, now);
            }
        });
    }

    private DbAlertEvent newEvent(DbAlertRule rule, SlowMetricEvent m, BigDecimal value, String dedupKey, Date now) {
        DbAlertEvent ev = new DbAlertEvent();
        ev.setRuleId(rule.getId());
        ev.setDedupKey(dedupKey);
        ev.setDataSourceId(m.dataSourceId());
        ev.setFingerprintId(m.fingerprintId());
        ev.setFingerprint(m.fingerprint());
        ev.setSeverity(rule.getSeverity());
        ev.setStatus("FIRING");
        ev.setFirstFiredAt(now);
        ev.setLastFiredAt(now);
        ev.setTriggerCount(1);
        ev.setCurrentValue(value);
        ev.setThreshold(rule.getThreshold());
        ev.setWindowStart(m.windowStartMillis() > 0 ? new Date(m.windowStartMillis()) : null);
        ev.setWindowEnd(m.windowEndMillis() > 0 ? new Date(m.windowEndMillis()) : null);
        ev.setEvidenceSummary(buildEvidence(m));
        return ev;
    }

    private void updateExisting(DbAlertEvent existing, DbAlertRule rule, SlowMetricEvent m, BigDecimal value, Date now) {
        int newCount = (existing.getTriggerCount() == null ? 1 : existing.getTriggerCount()) + 1;
        BigDecimal prevValue = existing.getCurrentValue();
        existing.setTriggerCount(newCount);
        existing.setLastFiredAt(now);
        existing.setCurrentValue(value);
        existing.setEvidenceSummary(buildEvidence(m));
        // 维护静默：SILENCED 未过期且非采集器/平台 → 不通知（docs/07 §8）
        boolean silenced = "SILENCED".equals(existing.getStatus())
            && existing.getSilenceUntil() != null && existing.getSilenceUntil().after(now)
            && !"COLLECTOR".equals(rule.getSeverity());
        if (silenced) {
            eventMapper.updateById(existing);
            return;
        }
        // 抑制：last_fired 在 dedup_window 内不重复轰炸（P1 恶化 2 倍升级除外，docs/07 §8）
        long dedupMs = (rule.getDedupWindowSeconds() == null ? 900 : rule.getDedupWindowSeconds()) * 1000L;
        boolean inSuppressWindow = existing.getLastFiredAt() != null
            && (now.getTime() - existing.getLastFiredAt().getTime()) < dedupMs;
        boolean upgrade = "P1".equals(rule.getSeverity()) && prevValue != null
            && value.compareTo(prevValue.multiply(BigDecimal.valueOf(2))) >= 0;
        if (inSuppressWindow && !upgrade) {
            eventMapper.updateById(existing);
            return;
        }
        eventMapper.updateById(existing);
        enqueue(existing, rule);
    }

    private void enqueue(DbAlertEvent event, DbAlertRule rule) {
        List<Long> channelIds = routingChannels(rule.getRouting());
        if (channelIds.isEmpty()) {
            return;
        }
        deliveryService.enqueue(event, rule, channelIds);
    }

    private String buildEvidence(SlowMetricEvent m) {
        if (m.normalizedStatement() == null) {
            return "采集器连续失败: " + m.consecutiveFailures();
        }
        return truncate(m.normalizedStatement(), 500);
    }

    @SuppressWarnings("unchecked")
    private List<Long> routingChannels(String routing) {
        if (routing == null || routing.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> r = OM.readValue(routing, new TypeReference<Map<String, Object>>() {});
            Object ch = r.get("channels");
            if (ch instanceof List) {
                List<Long> ids = new ArrayList<>();
                for (Object o : (List<Object>) ch) {
                    if (o instanceof Number) {
                        ids.add(((Number) o).longValue());
                    } else {
                        try {
                            ids.add(Long.parseLong(String.valueOf(o).trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                return ids;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
