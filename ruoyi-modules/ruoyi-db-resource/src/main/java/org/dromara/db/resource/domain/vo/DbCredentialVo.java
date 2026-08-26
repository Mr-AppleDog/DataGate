package org.dromara.db.resource.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.db.resource.domain.DbCredential;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 凭据元信息视图对象（CRED-004：只写不可读回）。
 *
 * <p>只暴露非秘密元信息；密文、Nonce、DEK、明文永远不会出现在任何 API 响应中。</p>
 *
 * @author DataGate
 */
@Data
@AutoMapper(target = DbCredential.class)
public class DbCredentialVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long dataSourceId;

    /**
     * QUERY/CHANGE/MONITOR
     */
    private String purpose;

    /**
     * 用户名（非秘密，受限管理页可显示）
     */
    private String username;

    /**
     * ACTIVE/DISABLED/INVALID
     */
    private String status;

    private Date lastVerifiedAt;

    private Date rotateDueAt;

    private Date createTime;
}
