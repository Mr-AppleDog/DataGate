package org.dromara.db.resource.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 凭据密文版本（docs/04 第 3.5 节，CRED-002/004）。
 *
 * <p>安全约束：不使用 Lombok {@code @Data}/{@code @ToString}，
 * 密文/Nonce/DEK 字段绝不进入 toString（docs/11 第 8 节）。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_credential_version")
public class DbCredentialVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long credentialId;

    private Integer versionNo;

    /**
     * 秘密密文（含 GCM tag）。禁止序列化到任何 API 响应。
     */
    private byte[] ciphertext;

    /**
     * 内容加密 Nonce。禁止外泄。
     */
    private byte[] nonce;

    /**
     * KEK 包裹后的 DEK。禁止外泄。
     */
    private byte[] wrappedDek;

    /**
     * DEK 包裹 Nonce。禁止外泄。
     */
    private byte[] dekNonce;

    private String algorithm;

    private String keyVersion;

    /**
     * 不可逆指纹（重复检测用）
     */
    private String secretFingerprint;

    /**
     * PENDING/VERIFIED/ACTIVE/RETIRED/INVALID
     */
    private String status;

    private Date verifiedAt;

    private Date activatedAt;

    private Date retiredAt;

    private Long createdBy;

    private Date createdAt;

    /**
     * 固定掩码：绝不输出密文/Nonce 内容
     */
    @Override
    public String toString() {
        return "DbCredentialVersion(id=" + id + ", credentialId=" + credentialId
            + ", versionNo=" + versionNo + ", status=" + status + ", keyVersion=" + keyVersion
            + ", secret=******)";
    }
}
