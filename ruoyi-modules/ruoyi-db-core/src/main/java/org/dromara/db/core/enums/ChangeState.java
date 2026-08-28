package org.dromara.db.core.enums;

/**
 * SQL 变更工单状态机（docs/05 §4.5）。
 *
 * <pre>
 * DRAFT → PRECHECKING → PRECHECKED → PENDING_APPROVAL → APPROVED
 *              └→ PRECHECK_FAILED          ├→ REJECTED
 * APPROVED → SCHEDULED → RUNNING → SUCCEEDED
 *                         ├→ FAILED
 *                         └→ UNKNOWN
 * </pre>
 *
 * <p>SQL 内容变化后必须回到 DRAFT 并清空原审批结论（docs/05 §4.5）。
 *
 * @author DataGate
 */
public enum ChangeState {

    DRAFT,
    PRECHECKING,
    PRECHECKED,
    PENDING_APPROVAL,
    APPROVED,
    SCHEDULED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    PRECHECK_FAILED,
    REJECTED,
    CANCELED
}
