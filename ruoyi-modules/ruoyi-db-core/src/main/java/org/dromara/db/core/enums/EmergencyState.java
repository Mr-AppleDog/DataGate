package org.dromara.db.core.enums;

/**
 * 紧急访问状态机（docs/03 §10.4、docs/10 M5-04）。
 *
 * <pre>
 * DRAFT → PENDING_APPROVAL → APPROVED → ACTIVE → EXPIRED/REVOKED
 *                     ├→ REJECTED         (复盘) → POST_MORTEM_PENDING → POST_MORTEM_DONE
 *                     └→ CANCELED
 * </pre>
 *
 * <p>不续期，到期后只能重新申请。复盘须在开通后 24h 内补充。
 *
 * @author DataGate
 */
public enum EmergencyState {

    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    ACTIVE,
    EXPIRED,
    REVOKED,
    REJECTED,
    CANCELED,
    POST_MORTEM_PENDING,
    POST_MORTEM_DONE
}
