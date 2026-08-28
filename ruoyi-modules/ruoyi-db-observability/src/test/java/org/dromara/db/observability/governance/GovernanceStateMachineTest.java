package org.dromara.db.observability.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理状态机测试（docs/05 §4.6）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("慢查询治理状态机")
class GovernanceStateMachineTest {

    @Test
    @DisplayName("正向主线：DISCOVERED→CLAIMED→IN_PROGRESS→PENDING_VERIFY→RESOLVED")
    void mainPathAllowed() {
        assertTrue(GovernanceStateMachine.canTransition("DISCOVERED", "CLAIMED"));
        assertTrue(GovernanceStateMachine.canTransition("CLAIMED", "IN_PROGRESS"));
        assertTrue(GovernanceStateMachine.canTransition("IN_PROGRESS", "PENDING_VERIFY"));
        assertTrue(GovernanceStateMachine.canTransition("PENDING_VERIFY", "RESOLVED"));
    }

    @Test
    @DisplayName("验证失败回退：PENDING_VERIFY→IN_PROGRESS")
    void verifyFailBackAllowed() {
        assertTrue(GovernanceStateMachine.canTransition("PENDING_VERIFY", "IN_PROGRESS"));
    }

    @Test
    @DisplayName("任意活跃态→IGNORED 旁路")
    void ignoreAllowedFromActive() {
        assertTrue(GovernanceStateMachine.canTransition("DISCOVERED", "IGNORED"));
        assertTrue(GovernanceStateMachine.canTransition("CLAIMED", "IGNORED"));
        assertTrue(GovernanceStateMachine.canTransition("IN_PROGRESS", "IGNORED"));
        assertTrue(GovernanceStateMachine.canTransition("PENDING_VERIFY", "IGNORED"));
    }

    @Test
    @DisplayName("IGNORED→DISCOVERED 重开（再次恶化或人工恢复）")
    void ignoredReopenAllowed() {
        assertTrue(GovernanceStateMachine.canTransition("IGNORED", "DISCOVERED"));
    }

    @Test
    @DisplayName("跳过中间态不允许：DISCOVERED→RESOLVED")
    void skipNotAllowed() {
        assertFalse(GovernanceStateMachine.canTransition("DISCOVERED", "RESOLVED"));
        assertFalse(GovernanceStateMachine.canTransition("DISCOVERED", "PENDING_VERIFY"));
        assertFalse(GovernanceStateMachine.canTransition("CLAIMED", "RESOLVED"));
    }

    @Test
    @DisplayName("RESOLVED 终态不可迁移")
    void resolvedTerminal() {
        assertFalse(GovernanceStateMachine.canTransition("RESOLVED", "CLAIMED"));
        assertFalse(GovernanceStateMachine.canTransition("RESOLVED", "DISCOVERED"));
    }
}
