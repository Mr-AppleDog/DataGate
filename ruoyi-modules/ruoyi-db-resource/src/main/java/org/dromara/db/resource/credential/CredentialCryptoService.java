package org.dromara.db.resource.credential;

import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.KekProvider;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 凭据信封加密服务（CRED-002/003/004，docs/08 第 6.1 节）。
 *
 * <p>每个凭据版本生成独立 DEK（32B 随机）；DEK 由 KEK 包裹（AES-256-GCM，AAD 绑定 keyVersion）；
 * 凭据正文由 DEK 加密（AES-256-GCM，AAD 绑定 credentialId|dataSourceId|purpose|versionNo，
 * 防止密文被搬移到其他凭据——M1-02 验收：替换 credentialId 的密文解密必须失败）。</p>
 *
 * <p>本类不记录任何日志；异常 message 不携带秘密、密文或密钥材料。</p>
 *
 * @author DataGate
 */
public class CredentialCryptoService {

    private static final String ALGORITHM = "AES-256-GCM";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int DEK_BYTES = 32;
    private static final String WRAP_AAD_PREFIX = "DataGate-KEK-WRAP|";

    private final KekProvider kekProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCryptoService(KekProvider kekProvider) {
        this.kekProvider = kekProvider;
    }

    /**
     * 加密结果（对应 dbg_credential_version 各 bytea 字段）
     */
    public record Envelope(
        byte[] ciphertext,
        byte[] nonce,
        byte[] wrappedDek,
        byte[] dekNonce,
        String algorithm,
        String keyVersion,
        String secretFingerprint
    ) {
    }

    /**
     * 加密凭据明文（生成新版本密文包）
     *
     * @param credentialId 凭据 ID（AAD 绑定）
     * @param dataSourceId 数据源 ID（AAD 绑定）
     * @param purpose      用途（AAD 绑定）
     * @param versionNo    版本号（AAD 绑定）
     * @param plaintext    秘密明文（本方法不销毁调用方的 SecretValue）
     */
    public Envelope encrypt(Long credentialId, Long dataSourceId, CredentialPurpose purpose,
                            int versionNo, SecretValue plaintext) {
        byte[] kek = kekProvider.currentKek();
        String keyVersion = kekProvider.currentKeyVersion();
        if (kek == null || keyVersion == null) {
            throw new IllegalStateException("KEK 不可用");
        }
        byte[] dek = new byte[DEK_BYTES];
        byte[] dekNonce = randomNonce();
        byte[] nonce = randomNonce();
        byte[] plainBytes = null;
        try {
            secureRandom.nextBytes(dek);
            byte[] wrappedDek = aesGcm(true, kek, dekNonce, dek, wrapAad(keyVersion));
            byte[] aad = contentAad(credentialId, dataSourceId, purpose, versionNo);
            plainBytes = toBytes(plaintext);
            byte[] ciphertext = aesGcm(true, dek, nonce, plainBytes, aad);
            String fingerprint = fingerprint(kek, plainBytes);
            return new Envelope(ciphertext, nonce, wrappedDek, dekNonce, ALGORITHM, keyVersion, fingerprint);
        } finally {
            Arrays.fill(kek, (byte) 0);
            Arrays.fill(dek, (byte) 0);
            if (plainBytes != null) {
                Arrays.fill(plainBytes, (byte) 0);
            }
        }
    }

    /**
     * 解密凭据版本，明文仅经回调短时暴露
     *
     * @param envelope   密文包
     * @param credentialId 期望的凭据 ID（AAD 校验，搬移检测）
     * @param dataSourceId 期望的数据源 ID
     * @param purpose    期望的用途
     * @param versionNo  期望的版本号
     * @param consumer   明文消费者（禁止记录日志或持久化）
     */
    public void decrypt(Envelope envelope, Long credentialId, Long dataSourceId,
                        CredentialPurpose purpose, int versionNo, SecretValue.SecretConsumer consumer) {
        byte[] kek = kekProvider.kekByVersion(envelope.keyVersion());
        if (kek == null) {
            throw new IllegalStateException("KEK 版本不可用");
        }
        byte[] dek = null;
        byte[] plainBytes = null;
        try {
            dek = aesGcm(false, kek, envelope.dekNonce(), envelope.wrappedDek(), wrapAad(envelope.keyVersion()));
            byte[] aad = contentAad(credentialId, dataSourceId, purpose, versionNo);
            plainBytes = aesGcm(false, dek, envelope.nonce(), envelope.ciphertext(), aad);
            char[] chars = toChars(plainBytes);
            try {
                consumer.accept(chars);
            } finally {
                Arrays.fill(chars, '\0');
            }
        } finally {
            Arrays.fill(kek, (byte) 0);
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
            if (plainBytes != null) {
                Arrays.fill(plainBytes, (byte) 0);
            }
        }
    }

    /**
     * 不可逆指纹（HMAC-SHA256，key 派生自 KEK）：判断是否为相同秘密，不可反推明文
     */
    public String fingerprintOf(Long credentialId, Long dataSourceId, CredentialPurpose purpose,
                                int versionNo, SecretValue plaintext) {
        byte[] kek = kekProvider.currentKek();
        byte[] plainBytes = null;
        try {
            plainBytes = toBytes(plaintext);
            return fingerprint(kek, plainBytes);
        } finally {
            Arrays.fill(kek, (byte) 0);
            if (plainBytes != null) {
                Arrays.fill(plainBytes, (byte) 0);
            }
        }
    }

    private byte[] wrapAad(String keyVersion) {
        return (WRAP_AAD_PREFIX + keyVersion).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] contentAad(Long credentialId, Long dataSourceId, CredentialPurpose purpose, int versionNo) {
        return (credentialId + "|" + dataSourceId + "|" + purpose.name() + "|" + versionNo)
            .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        return nonce;
    }

    private byte[] aesGcm(boolean encryptMode, byte[] key, byte[] nonce, byte[] input, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(encryptMode ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            // 不携带秘密上下文的通用失败
            throw new IllegalStateException("凭据加解密失败", e);
        }
    }

    private String fingerprint(byte[] kek, byte[] plainBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hmacKey = digest.digest("DataGate-FP".getBytes(StandardCharsets.UTF_8));
            hmacKey = digest.digest(concat(hmacKey, kek));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(plainBytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("指纹计算失败", e);
        }
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private byte[] toBytes(SecretValue secret) {
        final byte[][] holder = new byte[1][];
        secret.useSecret(chars -> holder[0] = toBytes(chars));
        return holder[0];
    }

    private byte[] toBytes(char[] chars) {
        CharBuffer cb = CharBuffer.wrap(chars);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
        return bytes;
    }

    private char[] toChars(byte[] bytes) {
        CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] chars = new char[cb.remaining()];
        cb.get(chars);
        return chars;
    }
}
