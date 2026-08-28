package org.dromara.db.workflow.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 紧急访问工单（docs/03 §10.4、docs/04，ALT-001）。
 *
 * <p>双人审批 + 2h 临时授权 + TOTP + 事件编号 + 事后 24h 复盘；不续期。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_emergency_access")
public class DbEmergencyAccess implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;
    private String requestNo;
    /** 事件编号（必填） */
    private String eventNo;
    private Long applicantId;
    private Long approver1Id;
    private Long approver2Id;
    private Long targetResourceId;
    private String targetAction;
    private String reason;

    private Date validFrom;
    private Date validUntil;
    private Long grantId;
    private String status;

    /** 复盘截止（开通后 24h） */
    private Date postMortemDueAt;
    private String postMortemContent;
    private Date postMortemAt;

    private Long workflowInstanceId;
    private Integer version;

    private Long createBy;
    private Date createTime;
    private Long updateBy;
    private Date updateTime;
    private String delFlag;
}
