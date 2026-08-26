package org.dromara.db.resource.credential;

import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.KekProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 凭据信封加密契约测试（CRED-002/003，docs/10 M1-02 验收项的单元部分）
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("凭据信封加密（AES-256-GCM + KEK 外置）")
class CredentialCryptoServiceTest {

    private CredentialCryptoService cryptoService;

    @BeforeEach
    void setUp() {
        byte[] kek = new byte[32];
        new SecureRandom().nextBytes(kek);
        Map<String, byte[]> keys = new ConcurrentHashMap<>(Map.of("v1", kek));
        KekProvider kekProvider = new KekProvider() {
            @Override
            public String currentKeyVersion() {
                return "v1";
            }

            @Override
            public byte[] currentKek() {
                return keys.get("v1").clone();
            }

            @Override
            public byte[] kekByVersion(String keyVersion) {
                byte[] k = keys.get(keyVersion);
                return k == null ? null : k.clone();
            }
        };
        cryptoService = new CredentialCryptoService(kekProvider);
    }

    @Test
    @DisplayName("加密-解密回环成功")
    void encryptDecryptRoundtrip() {
        SecretValue secret = SecretValue.of("p@ssw0rd-canary-001");
        CredentialCryptoService.Envelope envelope =
            cryptoService.encrypt(1001L, 2002L, CredentialPurpose.QUERY, 1, secret);

        assertEquals("AES-256-GCM", envelope.algorithm());
        assertEquals("v1", envelope.keyVersion());

        StringBuilder decrypted = new StringBuilder();
        cryptoService.decrypt(envelope, 1001L, 2002L, CredentialPurpose.QUERY, 1,
            chars -> decrypted.append(chars));
        assertEquals("p@ssw0rd-canary-001", decrypted.toString());
        secret.destroy();
    }

    @Test
    @DisplayName("密文搬移到其他 credentialId 解密失败（M1-02 验收：AAD 绑定）")
    void ciphertextMovedToAnotherCredentialFails() {
        SecretValue secret = SecretValue.of("p@ssw0rd-canary-002");
        CredentialCryptoService.Envelope envelope =
            cryptoService.encrypt(1001L, 2002L, CredentialPurpose.QUERY, 1, secret);

        // 攻击者把密文搬移到 credentialId=9999 的凭据记录
        assertThrows(IllegalStateException.class, () ->
            cryptoService.decrypt(envelope, 9999L, 2002L, CredentialPurpose.QUERY, 1, chars -> {
            }));
        // 搬移到其他数据源同样失败
        assertThrows(IllegalStateException.class, () ->
            cryptoService.decrypt(envelope, 1001L, 8888L, CredentialPurpose.QUERY, 1, chars -> {
            }));
        // 搬移到其他用途同样失败
        assertThrows(IllegalStateException.class, () ->
            cryptoService.decrypt(envelope, 1001L, 2002L, CredentialPurpose.CHANGE, 1, chars -> {
            }));
        secret.destroy();
    }

    @Test
    @DisplayName("指纹稳定且不同秘密指纹不同")
    void fingerprintIsDeterministic() {
        SecretValue a1 = SecretValue.of("same-secret");
        SecretValue a2 = SecretValue.of("same-secret");
        SecretValue b = SecretValue.of("other-secret");
        String fa1 = cryptoService.encrypt(1L, 2L, CredentialPurpose.QUERY, 1, a1).secretFingerprint();
        String fa2 = cryptoService.encrypt(1L, 2L, CredentialPurpose.QUERY, 2, a2).secretFingerprint();
        String fb = cryptoService.encrypt(1L, 2L, CredentialPurpose.QUERY, 3, b).secretFingerprint();
        assertEquals(fa1, fa2);
        assertNotEquals(fa1, fb);
        a1.destroy();
        a2.destroy();
        b.destroy();
    }

    @Test
    @DisplayName("指纹不含明文特征（长度恒定 64 hex）")
    void fingerprintFixedLength() {
        SecretValue secret = SecretValue.of("x");
        String fp = cryptoService.encrypt(1L, 2L, CredentialPurpose.MONITOR, 1, secret).secretFingerprint();
        assertEquals(64, fp.length());
        secret.destroy();
    }
}
