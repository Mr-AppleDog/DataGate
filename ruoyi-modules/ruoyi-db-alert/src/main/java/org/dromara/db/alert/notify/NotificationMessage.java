package org.dromara.db.alert.notify;

/**
 * 通知消息（docs/07 §9.1 最小内容，脱敏渲染）。
 * 严禁包含凭据/连接串/Token/完整原 SQL/Redis value/查询结果。
 *
 * @param severity        严重级别 P1/P2/P3/COLLECTOR
 * @param title           通知标题（含级别与环境数据源别名）
 * @param body            脱敏渲染正文（指标/阈值/窗口/趋势/指纹摘要/负责人/治理状态/详情链接）
 * @param detailLink      平台详情链接
 *
 * @author DataGate
 */
public record NotificationMessage(String severity, String title, String body, String detailLink) {
}
