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
 * 用户安全状态（dbg_user_security，IAM-002/IAM-003）。
 *
 * <p>不存任何秘密；仅保存首次改密标记与密码策略版本。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_user_security")
public class DbUserSecurity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private Long userId;

    /**
     * 是否必须修改初始密码后才允许登录业务功能
     */
    private Boolean mustChangePwd;

    /**
     * 最近一次密码修改时间
     */
    private Date pwdChangedAt;

    /**
     * 最近一次设密时适用的密码策略版本
     */
    private Integer policyVersion;

    private Date createTime;

    private Date updateTime;
}
