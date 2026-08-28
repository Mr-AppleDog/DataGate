package org.dromara.db.core.enums;

/**
 * 导出工单状态机（docs/05 §4.4）。
 *
 * <pre>
 * DRAFT → PENDING_APPROVAL → APPROVED → QUEUED → RUNNING → SUCCEEDED → EXPIRED → DELETED
 *                     ├→ REJECTED          ├→ FAILED
 *                     └→ CANCELED          └→ CANCELED
 * </pre>
 *
 * @author DataGate
 */
public enum ExportState {

    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    EXPIRED,
    DELETED,
    REJECTED,
    CANCELED,
    FAILED
}
