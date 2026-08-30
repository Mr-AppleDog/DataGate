package org.dromara.db.resource.spi.impl;

import org.dromara.db.core.spi.KekProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件挂载 KEK Provider（docs/08 第 6.1 节：生产经只读 Secret 挂载；试用环境允许本地密钥文件）。
 *
 * <p>密钥文件格式：每行 {@code 版本:BASE64(32字节密钥)}，第一行为当前版本。示例：</p>
 * <pre>
 * v1:9F3k...（base64，32字节）
 * </pre>
 *
 * <p>失败关闭：文件缺失、格式错误或密钥长度不足 32 字节时，应用启动失败。</p>
 *
 * @author DataGate
 */
@Configuration
@EnableConfigurationProperties(FileKekProvider.Properties.class)
public class FileKekProvider {

    /**
     * KEK 文件路径配置
     *
     * @param kekFile KEK 文件路径（只读挂载）
     */
    @ConfigurationProperties(prefix = "datagate.security.credential")
    public record Properties(String kekFile) {
    }

    @Bean
    @ConditionalOnMissingBean(KekProvider.class)
    public KekProvider kekProvider(Properties properties) {
        String kekFile = properties.kekFile();
        if (kekFile == null || kekFile.isBlank()) {
            throw new IllegalStateException(
                "datagate.security.credential.kek-file 未配置：KEK 必须通过只读文件挂载提供（CRED-003），应用失败关闭");
        }
        Map<String, byte[]> keys = new HashMap<>();
        String currentVersion;
        try {
            List<String> lines = Files.readAllLines(Path.of(kekFile), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IllegalStateException("KEK 文件为空");
            }
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(":", 2);
                if (parts.length != 2) {
                    throw new IllegalStateException("KEK 文件格式错误（应为 版本:BASE64）");
                }
                byte[] key = Base64.getDecoder().decode(parts[1].trim());
                if (key.length != 32) {
                    throw new IllegalStateException("KEK 长度必须为 32 字节（AES-256）");
                }
                keys.put(parts[0].trim(), key);
            }
            currentVersion = lines.stream().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .findFirst()
                .map(l -> l.split(":", 2)[0].trim())
                .orElseThrow();
        } catch (IOException e) {
            throw new IllegalStateException(
                "KEK 文件不可读，应用失败关闭；开发环境请先运行 script/datagate/init-dev-kek.ps1（Windows）"
                    + " 或 script/datagate/init-dev-kek.sh（Linux/macOS）",
                e);
        }
        String version = currentVersion;
        return new KekProvider() {
            @Override
            public String currentKeyVersion() {
                return version;
            }

            @Override
            public byte[] currentKek() {
                byte[] key = keys.get(version);
                return key == null ? null : key.clone();
            }

            @Override
            public byte[] kekByVersion(String keyVersion) {
                byte[] key = keys.get(keyVersion);
                return key == null ? null : key.clone();
            }
        };
    }
}
