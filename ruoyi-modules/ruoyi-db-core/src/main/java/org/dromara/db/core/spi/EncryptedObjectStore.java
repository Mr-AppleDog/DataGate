package org.dromara.db.core.spi;

import org.dromara.db.core.domain.EncryptedObject;

import java.io.InputStream;
import java.util.Optional;

/**
 * 加密对象存储（docs/06 §12、docs/04 §5.6）。
 *
 * <p>导出文件以随机对象键 + 服务端加密（信封加密，KEK 外置）存储；不保存可公开访问 URL。
 * create 流式读取（避免大对象全量入内存）；read 仅供持有票据的服务端下载解密。</p>
 *
 * @author DataGate
 */
public interface EncryptedObjectStore {

    /**
     * 流式写入并加密存储。
     *
     * @param in        内容流（调用方负责关闭）
     * @param expectedSize 预期字节数（可 -1 未知）
     * @return 加密对象元信息（objectKey/fileHash/encryptionKeyRef）
     */
    EncryptedObject create(InputStream in, long expectedSize);

    /**
     * 读取并解密对象内容流。对象不存在或密钥不可用时返回 empty（失败关闭）。
     */
    Optional<InputStream> read(String objectKey, String encryptionKeyRef);

    /**
     * 删除对象（生命周期到期/撤销）。幂等。
     */
    void delete(String objectKey);
}
