package org.dromara.db.alert.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 通知通道（docs/04 §8.3 + docs/07 §9）。秘密不进 config，经 secret_reference 引用凭据保险箱。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_notification_channel")
public class DbNotificationChannel extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    /**
     * DINGTALK/SMTP/WEBHOOK/WECHAT_WORK/FEISHU
     */
    private String type;

    private String name;

    /**
     * 非秘密配置 JSON（webhook URL/smtp host+from+to，不含密码类键）
     */
    private String config;

    /**
     * 秘密引用（credentialId，与数据源凭据同等级加密托管）
     */
    private String secretReference;

    private String status;

    private Date lastVerifiedAt;

    @TableLogic
    private String delFlag;
}
