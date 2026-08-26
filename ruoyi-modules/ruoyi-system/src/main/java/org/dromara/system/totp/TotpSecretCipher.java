package org.dromara.system.totp;

import org.dromara.db.core.spi.KekProvider;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * TOTP 密钥加密器（docs/08：TOTP Secret 属于受保护秘密）。
 *
 * <p>用 KEK 直接 AES-256-GCM 加密（单密钥场景无需 DEK 包裹），
 * AAD 绑定 userId：密文搬移到其他用户记录后解密必然失败。</p>
 *
 * @author DataGate
 */
@Component
public class TotpSecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final String AAD_PREFIX = "DataGate-TOTP|";

    private final KekProvider kekProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpSecretCipher(KekProvider kekProvider) {
        this.kekProvider = kekProvider;
    }

    /**
     * 加密结果
     */
    public record Sealed(byte[] ciphertext, byte[] nonce, String keyVersion) {
    }

    /**
     * 加密 TOTP 密钥
     */
    public Sealed seal(Long userId, byte[] secret) {
        byte[] kek = kekProvider.currentKek();
        if (kek == null) {
            throw new IllegalStateException("KEK 不可用");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            byte[] ciphertext = aesGcm(true, kek, nonce, secret, aad(userId));
            return new Sealed(ciphertext, nonce, kekProvider.currentKeyVersion());
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /**
     * 解密 TOTP 密钥（调用方使用后清零返回数组）
     */
    public byte[] unseal(Long userId, byte[] ciphertext, byte[] nonce, String keyVersion) {
        byte[] kek = kekProvider.kekByVersion(keyVersion);
        if (kek == null) {
            throw new IllegalStateException("KEK 版本不可用");
        }
        try {
            return aesGcm(false, kek, nonce, ciphertext, aad(userId));
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private byte[] aad(Long userId) {
        return (AAD_PREFIX + userId).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] aesGcm(boolean encryptMode, byte[] key, byte[] nonce, byte[] input, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(encryptMode ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP 密钥加解密失败", e);
        }
    }
}
