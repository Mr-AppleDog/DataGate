package org.dromara.db.core.spi;

/**
 * KEK（密钥加密密钥）提供者 SPI（docs/08 第 6.1 节，CRED-003）。
 *
 * <p>平台级 SPI：凭据保险箱、TOTP 密钥保护等所有信封加密能力共用。
 * KEK 不存业务数据库、不进 Git、不进镜像。生产经只读 Secret 挂载或企业密钥服务提供。
 * 实现方禁止将密钥材料写入日志或异常 message。</p>
 *
 * @author DataGate
 */
public interface KekProvider {

    /**
     * 当前 KEK 版本标识（记录到密文版本，用于轮换与恢复）
     */
    String currentKeyVersion();

    /**
     * 当前 KEK（32 字节）。返回值使用后由调用方清零。
     */
    byte[] currentKek();

    /**
     * 按版本获取 KEK（用于解密历史版本；KEK 轮换只重包裹 DEK）
     *
     * @param keyVersion 密钥版本
     * @return 32 字节 KEK；版本不存在返回 null
     */
    byte[] kekByVersion(String keyVersion);
}
