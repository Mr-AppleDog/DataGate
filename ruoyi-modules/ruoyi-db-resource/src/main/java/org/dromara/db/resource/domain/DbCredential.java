package org.dromara.db.resource.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 凭据主表（docs/04 第 3.4 节，CRED-001）。仅保存非秘密元信息。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_credential")
public class DbCredential extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long dataSourceId;

    /**
     * QUERY/CHANGE/MONITOR
     */
    private String purpose;

    /**
     * 用户名（非秘密，可受限显示）
     */
    private String username;

    private Long activeVersionId;

    /**
     * ACTIVE/DISABLED/INVALID
     */
    private String status;

    private Date lastVerifiedAt;

    private Date rotateDueAt;

    @TableLogic
    private String delFlag;
}
