package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 用户 TOTP（dbg_user_totp，IAM-005）。
 *
 * <p>安全约束：不使用 Lombok {@code @Data}/{@code @ToString}，
 * 密文/Nonce 绝不进入 toString；恢复码只存 SHA-256 哈希。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_user_totp")
public class DbUserTotp implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private Long userId;

    /**
     * KEK 直接加密的 TOTP 密钥。禁止序列化到任何 API 响应。
     */
    private byte[] ciphertext;

    /**
     * GCM Nonce。禁止外泄。
     */
    private byte[] nonce;

    private String algorithm;

    private String keyVersion;

    /**
     * PENDING/ACTIVE/DISABLED
     */
    private String status;

    /**
     * 恢复码 SHA-256 哈希数组（JSON），使用后即移除
     */
    private String recoveryHashes;

    private Date boundAt;

    private Date lastUsedAt;

    /**
     * 上次成功验证的时间步（防重放）
     */
    private Long lastStep;

    private Date createTime;

    private Date updateTime;

    /**
     * 固定掩码：绝不输出密文内容
     */
    @Override
    public String toString() {
        return "DbUserTotp(userId=" + userId + ", status=" + status
            + ", keyVersion=" + keyVersion + ", secret=******)";
    }
}
