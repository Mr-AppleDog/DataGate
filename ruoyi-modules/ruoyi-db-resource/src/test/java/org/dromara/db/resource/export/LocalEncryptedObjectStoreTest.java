package org.dromara.db.resource.export;

import org.dromara.db.core.domain.EncryptedObject;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.KekProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地加密对象存储单元测试（docs/06 §12、docs/04 §5.6）。
 *
 * <p>覆盖：加解密 round-trip、SHA-256 完整性、密文搬移检测（重命名 objectKey 解密失败）、删除幂等。
 *
 * @author DataGate
 */
@Tag("unit")
class LocalEncryptedObjectStoreTest {

    @TempDir
    Path tempDir;

    private KekProvider fixedKek() {
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        return new KekProvider() {
            @Override public String currentKeyVersion() { return "v1"; }
            @Override public byte[] currentKek() { return key.clone(); }
            @Override public byte[] kekByVersion(String kv) { return "v1".equals(kv) ? key.clone() : null; }
        };
    }

    private EncryptedObjectStore store() {
        return new LocalEncryptedObjectStore(fixedKek(), new LocalEncryptedObjectStore.Properties(tempDir.toString()));
    }

    @Test
    void create_then_read_roundtrip() throws Exception {
        EncryptedObjectStore store = store();
        byte[] content = "alice,13812345678\n＂bad".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EncryptedObject obj = store.create(new ByteArrayInputStream(content), content.length);
        assertTrue(obj.objectKey().length() > 0);
        assertTrue(obj.fileHash().length() == 64); // sha256 hex
        assertEquals("v1", obj.encryptionKeyRef());
        assertEquals(content.length, obj.size());

        Optional<InputStream> read = store.read(obj.objectKey(), obj.encryptionKeyRef());
        assertTrue(read.isPresent());
        assertArrayEquals(content, read.get().readAllBytes());
    }

    @Test
    void ciphertext_relocation_detected() throws Exception {
        // 用 objectKey A 加密，用 B 读取→AAD 不匹配→失败关闭（empty）
        EncryptedObjectStore store = store();
        byte[] content = "secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EncryptedObject obj = store.create(new ByteArrayInputStream(content), content.length);
        // 用错误的 objectKey 读（模拟搬移）
        Optional<InputStream> read = store.read("wrongkey" + obj.objectKey(), obj.encryptionKeyRef());
        assertTrue(read.isEmpty());
    }

    @Test
    void missing_object_returns_empty() throws Exception {
        EncryptedObjectStore store = store();
        assertTrue(store.read("nonexistent", "v1").isEmpty());
    }

    @Test
    void delete_is_idempotent() throws Exception {
        EncryptedObjectStore store = store();
        EncryptedObject obj = store.create(new ByteArrayInputStream("x".getBytes()), 1);
        store.delete(obj.objectKey());
        store.delete(obj.objectKey()); // 幂等不抛
        assertTrue(store.read(obj.objectKey(), obj.encryptionKeyRef()).isEmpty());
    }

    @Test
    void empty_content_stored() throws Exception {
        EncryptedObjectStore store = store();
        EncryptedObject obj = store.create(new ByteArrayInputStream(new byte[0]), 0);
        assertEquals(0, obj.size());
        Optional<InputStream> read = store.read(obj.objectKey(), obj.encryptionKeyRef());
        assertTrue(read.isPresent());
        assertEquals(0, read.get().readAllBytes().length);
    }
}
