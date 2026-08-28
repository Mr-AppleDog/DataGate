package org.dromara.db.resource.export;

import lombok.extern.slf4j.Slf4j;
import org.dromara.db.core.domain.EncryptedObject;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.KekProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地加密对象存储（docs/06 §12、docs/04 §5.6）。
 *
 * <p>导出文件以随机 objectKey 命名、信封加密（DEK 由 KEK 包裹，AES-256-GCM）存储于本地目录；
 * 不保存可公开访问 URL。AAD 绑定 objectKey 防密文搬移（重命名文件→解密失败）。
 * P0 本地存储；MinIO/S3 为后续替换实现（SPI 不变）。</p>
 *
 * <p>失败关闭：KEK 不可用→create 抛异常（导出失败，不入脏对象）；read 密钥/密文异常→empty。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@EnableConfigurationProperties(LocalEncryptedObjectStore.Properties.class)
public class LocalEncryptedObjectStore implements EncryptedObjectStore {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int DEK_BYTES = 32;
    private static final String MAGIC = "DGOBJ1";
    private static final String WRAP_AAD_PREFIX = "DGOBJ-WRAP|";
    private static final String CONTENT_AAD_PREFIX = "DGOBJ|";

    @ConfigurationProperties(prefix = "datagate.export")
    public record Properties(String objectDir) {
    }

    private final KekProvider kekProvider;
    private final Properties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalEncryptedObjectStore(KekProvider kekProvider, Properties properties) {
        this.kekProvider = kekProvider;
        this.properties = properties;
    }

    @Override
    public EncryptedObject create(InputStream in, long expectedSize) {
        byte[] kek = kekProvider.currentKek();
        String keyVersion = kekProvider.currentKeyVersion();
        if (kek == null || keyVersion == null) {
            throw new IllegalStateException("KEK 不可用，导出对象创建失败关闭");
        }
        String objectKey = UUID.randomUUID().toString().replace("-", "");
        byte[] dek = new byte[DEK_BYTES];
        byte[] dekNonce = new byte[NONCE_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] plaintext = null;
        byte[] wrappedDek = null;
        byte[] ciphertext = null;
        try {
            secureRandom.nextBytes(dek);
            secureRandom.nextBytes(dekNonce);
            secureRandom.nextBytes(nonce);
            plaintext = readAll(in);
            wrappedDek = aesGcm(true, kek, dekNonce, dek, wrapAad(keyVersion));
            ciphertext = aesGcm(true, dek, nonce, plaintext, contentAad(objectKey));
            String fileHash = sha256Hex(plaintext);
            Path file = resolveObject(objectKey);
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                writeUtf(out, MAGIC);
                writeUtf(out, keyVersion);
                writeBytes(out, dekNonce);
                writeBytes(out, wrappedDek);
                writeBytes(out, nonce);
                out.write(ciphertext);
            }
            return new EncryptedObject(objectKey, fileHash, keyVersion, plaintext.length);
        } catch (IOException e) {
            throw new IllegalStateException("导出对象写入失败", e);
        } finally {
            Arrays.fill(kek, (byte) 0);
            Arrays.fill(dek, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    @Override
    public Optional<InputStream> read(String objectKey, String encryptionKeyRef) {
        Path file = resolveObject(objectKey);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        byte[] kek = null;
        byte[] dek = null;
        byte[] plaintext = null;
        try {
            byte[] all = Files.readAllBytes(file);
            int p = 0;
            String magic = readUtf(all, p); p += utfLen(magic);
            if (!MAGIC.equals(magic)) {
                return Optional.empty();
            }
            String keyVersion = readUtf(all, p); p += utfLen(keyVersion);
            byte[] dekNonce = readBytes(all, p); p += recLen(dekNonce);
            byte[] wrappedDek = readBytes(all, p); p += recLen(wrappedDek);
            byte[] nonce = readBytes(all, p); p += recLen(nonce);
            byte[] ciphertext = new byte[all.length - p];
            System.arraycopy(all, p, ciphertext, 0, ciphertext.length);
            kek = kekProvider.kekByVersion(keyVersion);
            if (kek == null) {
                return Optional.empty();
            }
            dek = aesGcm(false, kek, dekNonce, wrappedDek, wrapAad(keyVersion));
            plaintext = aesGcm(false, dek, nonce, ciphertext, contentAad(objectKey));
            return Optional.of(new ByteArrayInputStream(plaintext));
        } catch (RuntimeException | IOException e) {
            // 解密失败/密文搬移→失败关闭，不向下载暴露细节
            log.warn("导出对象解密失败 objectKey={}", objectKey);
            return Optional.empty();
        } finally {
            if (kek != null) Arrays.fill(kek, (byte) 0);
            if (dek != null) Arrays.fill(dek, (byte) 0);
            // plaintext 由调用方读取后由 ByteArrayInputStream 持有；下载完由调用方负责
        }
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        Path file = resolveObject(objectKey);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("导出对象删除失败 objectKey={}", objectKey, e);
        }
    }

    // ====================== 内部 ======================

    private Path resolveObject(String objectKey) {
        String dir = properties.objectDir();
        String base = (dir == null || dir.isBlank())
            ? System.getProperty("java.io.tmpdir") + "/datagate-objects" : dir;
        return Path.of(base, objectKey + ".bin");
    }

    private static byte[] wrapAad(String keyVersion) {
        return (WRAP_AAD_PREFIX + keyVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] contentAad(String objectKey) {
        return (CONTENT_AAD_PREFIX + objectKey).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] aesGcm(boolean encryptMode, byte[] key, byte[] nonce, byte[] input, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(encryptMode ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("对象加解密失败", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    // ---- 简易长度前缀记录格式 ----
    private static void writeUtf(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(intToBytes(b.length));
        out.write(b);
    }

    private static void writeBytes(OutputStream out, byte[] b) throws IOException {
        out.write(intToBytes(b.length));
        out.write(b);
    }

    private static String readUtf(byte[] all, int off) {
        int len = readInt(all, off);
        return new String(all, off + 4, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(byte[] all, int off) {
        int len = readInt(all, off);
        byte[] b = new byte[len];
        System.arraycopy(all, off + 4, b, 0, len);
        return b;
    }

    private static int utfLen(String s) {
        return 4 + s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static int recLen(byte[] b) {
        return 4 + b.length;
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
            | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }
}
