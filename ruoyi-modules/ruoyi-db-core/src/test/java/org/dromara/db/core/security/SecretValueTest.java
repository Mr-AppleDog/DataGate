package org.dromara.db.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SecretValue 安全契约测试（AUD/CRED：秘密不得经 toString/日志泄漏）
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("SecretValue 安全契约")
class SecretValueTest {

    @Test
    @DisplayName("toString 永远返回固定掩码")
    void toStringNeverRevealsValue() {
        SecretValue secret = SecretValue.of("super-secret-password");
        assertEquals(SecretValue.MASK, secret.toString());
        assertFalse(secret.toString().contains("super"));
        secret.destroy();
    }

    @Test
    @DisplayName("useSecret 可用，destroy 后拒绝使用")
    void useAndDestroy() {
        SecretValue secret = SecretValue.of("abc".toCharArray());
        assertFalse(secret.isDestroyed());
        secret.useSecret(chars -> assertEquals(3, chars.length));
        secret.destroy();
        assertTrue(secret.isDestroyed());
        assertThrows(IllegalStateException.class, () -> secret.useSecret(chars -> {
        }));
    }

    @Test
    @DisplayName("空秘密被拒绝")
    void emptySecretRejected() {
        assertThrows(IllegalArgumentException.class, () -> SecretValue.of(""));
        assertThrows(IllegalArgumentException.class, () -> SecretValue.of(new char[0]));
    }
}
