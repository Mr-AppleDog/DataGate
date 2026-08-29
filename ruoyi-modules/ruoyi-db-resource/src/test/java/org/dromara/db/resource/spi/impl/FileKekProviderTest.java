package org.dromara.db.resource.spi.impl;

import org.dromara.db.core.spi.KekProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地文件 KEK Provider 契约测试（CRED-003）。
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("外置文件 KEK Provider")
class FileKekProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("版本加 Base64 的 32 字节 KEK 可加载")
    void validKekCanBeLoaded() throws Exception {
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        Path kekFile = tempDir.resolve("kek.txt");
        Files.writeString(kekFile, "v1:" + Base64.getEncoder().encodeToString(expected));

        KekProvider provider = new FileKekProvider()
            .kekProvider(new FileKekProvider.Properties(kekFile.toString()));

        assertEquals("v1", provider.currentKeyVersion());
        assertArrayEquals(expected, provider.currentKek());
        assertArrayEquals(expected, provider.kekByVersion("v1"));
    }

    @Test
    @DisplayName("文件缺失时失败关闭并给出开发初始化入口")
    void missingKekFailsClosedWithActionableMessage() {
        Path missingFile = tempDir.resolve("missing-kek.txt");

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new FileKekProvider().kekProvider(new FileKekProvider.Properties(missingFile.toString())));

        assertTrue(error.getMessage().contains("应用失败关闭"));
        assertTrue(error.getMessage().contains("init-dev-kek.ps1"));
    }

    @Test
    @DisplayName("非 32 字节 KEK 被拒绝")
    void shortKekIsRejected() throws Exception {
        Path kekFile = tempDir.resolve("short-kek.txt");
        Files.writeString(kekFile, "v1:" + Base64.getEncoder().encodeToString(new byte[16]));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new FileKekProvider().kekProvider(new FileKekProvider.Properties(kekFile.toString())));

        assertTrue(error.getMessage().contains("32 字节"));
    }
}
