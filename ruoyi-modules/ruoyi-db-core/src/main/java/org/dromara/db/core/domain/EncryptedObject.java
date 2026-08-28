package org.dromara.db.core.domain;

/**
 * 加密对象存储结果（docs/06 §12、docs/04 §5.6）。
 *
 * <p>导出文件以随机对象键 + 服务端加密存储，不保存可公开访问 URL。
 * encryptionKeyRef 引用信封 DEK 的封装（KEK 解封），下载时据此解密。</p>
 *
 * @param objectKey        随机对象键（非公开 URL）
 * @param fileHash         内容 SHA-256（完整性校验）
 * @param encryptionKeyRef 信封 DEK 引用（KEK 解封标识）
 * @param size             字节数
 * @author DataGate
 */
public record EncryptedObject(String objectKey, String fileHash, String encryptionKeyRef, long size) {
}
