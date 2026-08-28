package org.dromara.db.alert.notify;

import org.dromara.db.alert.domain.DbAlertEvent;
import org.dromara.db.alert.domain.DbAlertRule;
import org.springframework.stereotype.Component;

/**
 * 通知消息渲染（docs/07 §9.1）。默认展示脱敏模板摘要，永不发原文。
 *
 * @author DataGate
 */
@Component
public class MessageRenderer {

    private static final String TEMPLATE_VERSION = "slow-alert-v1";

    public String templateVersion() {
        return TEMPLATE_VERSION;
    }

    /**
     * 渲染脱敏通知消息。
     * evidence_summary 已由评估服务从 normalizedStatement 脱敏截断写入，本方法不再触碰原 SQL。
     */
    public NotificationMessage render(DbAlertEvent event, DbAlertRule rule) {
        String severity = event.getSeverity() == null ? "" : event.getSeverity();
        String ruleName = rule == null ? "" : (rule.getName() == null ? "" : rule.getName());
        String title = "DataGate 慢查询告警 [" + severity + "] " + ruleName;
        StringBuilder body = new StringBuilder();
        body.append("级别: ").append(severity).append("\n");
        body.append("规则: ").append(ruleName).append("\n");
        if (event.getDataSourceId() != null) {
            body.append("数据源ID: ").append(event.getDataSourceId()).append("\n");
        }
        if (event.getFingerprint() != null) {
            body.append("指纹: ").append(truncate(event.getFingerprint(), 32)).append("\n");
        }
        if (event.getEvidenceSummary() != null) {
            body.append("摘要: ").append(truncate(event.getEvidenceSummary(), 500)).append("\n");
        }
        if (event.getCurrentValue() != null && event.getThreshold() != null) {
            body.append("触发值: ").append(event.getCurrentValue()).append(" / 阈值: ").append(event.getThreshold()).append("\n");
        }
        if (event.getTriggerCount() != null) {
            body.append("触发次数: ").append(event.getTriggerCount()).append("\n");
        }
        if (event.getStatus() != null) {
            body.append("状态: ").append(event.getStatus()).append("\n");
        }
        return new NotificationMessage(severity, title, body.toString(),
            "/slow-query-fingerprints/" + (event.getFingerprintId() == null ? "" : event.getFingerprintId()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
