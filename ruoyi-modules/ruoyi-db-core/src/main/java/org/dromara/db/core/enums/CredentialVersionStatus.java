package org.dromara.db.core.enums;

/**
 * 凭据版本状态机（docs/05 第 4.2 节）。
 * 同一凭据同时只能有一个 ACTIVE 版本。
 *
 * @author DataGate
 */
public enum CredentialVersionStatus {

    PENDING,
    VERIFIED,
    ACTIVE,
    RETIRED,
    INVALID
}
