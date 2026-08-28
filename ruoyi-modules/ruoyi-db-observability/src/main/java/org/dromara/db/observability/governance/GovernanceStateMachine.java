package org.dromara.db.observability.governance;

import java.util.Set;

/**
 * 慢查询治理状态机（docs/05 §4.6 + docs/07 §10）。纯算法，可单测。
 *
 * DISCOVERED → CLAIMED → IN_PROGRESS → PENDING_VERIFY → RESOLVED
 *     │           │            │                 └→ IN_PROGRESS
 *     └───────────┴────────────┴───────────────→ IGNORED
 * IGNORED → DISCOVERED（再次严重恶化或人工恢复）
 *
 * RESOLVED 为终态（不可逆；再次恶化走指纹重开 IGNORED→DISCOVERED 不新增 REOPENED 持久状态）。
 *
 * @author DataGate
 */
public final class GovernanceStateMachine {

    private static final Set<String> ALLOWED = Set.of(
        "DISCOVERED:CLAIMED",
        "DISCOVERED:IGNORED",
        "CLAIMED:IN_PROGRESS",
        "CLAIMED:IGNORED",
        "CLAIMED:DISCOVERED",
        "IN_PROGRESS:PENDING_VERIFY",
        "IN_PROGRESS:IGNORED",
        "IN_PROGRESS:CLAIMED",
        "PENDING_VERIFY:RESOLVED",
        "PENDING_VERIFY:IN_PROGRESS",
        "PENDING_VERIFY:IGNORED",
        "IGNORED:DISCOVERED"
    );

    private GovernanceStateMachine() {
    }

    public static boolean canTransition(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.contains(from + ":" + to);
    }
}
