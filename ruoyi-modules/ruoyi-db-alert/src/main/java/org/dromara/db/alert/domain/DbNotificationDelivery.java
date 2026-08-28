package org.dromara.db.alert.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 通知投递（docs/04 §8.4 + docs/07 §9）。outbox 指数退避重试死信；正文只留哈希。
 * 事实表无审计基字段，不使用 BaseEntity。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_notification_delivery")
public class DbNotificationDelivery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long eventId;

    private Long channelId;

    private String templateVersion;

    private String targetSummary;

    /**
     * PENDING/SENDING/SENT/FAILED/DEAD（4xx→DEAD，429/5xx重试）
     */
    private String status;

    private Integer attemptCount;

    private Date nextRetryAt;

    private String responseCode;

    private String responseSummary;

    private String renderedBodyHash;

    private Date createdAt;

    private Date completedAt;
}
