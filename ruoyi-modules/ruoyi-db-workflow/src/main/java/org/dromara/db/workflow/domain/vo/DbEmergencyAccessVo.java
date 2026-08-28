package org.dromara.db.workflow.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 紧急访问视图（docs/03 §10.4）。
 *
 * @author DataGate
 */
@Data
public class DbEmergencyAccessVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String requestNo;
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
    private Date postMortemDueAt;
    private String postMortemContent;
    private Date postMortemAt;
    private Date createTime;
    private Date updateTime;
}
