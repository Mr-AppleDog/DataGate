package org.dromara.db.workflow.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 变更工单视图（docs/04 §5.7）。不回传 SQL 密文。
 *
 * @author DataGate
 */
@Data
public class DbChangeOrderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String requestNo;
    private Long applicantId;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private String changeType;
    private String fingerprint;
    private String resourceSnapshot;
    private String precheckResult;
    private String rollbackPlan;
    private String impactSummary;
    private Date executionWindowStart;
    private Date executionWindowEnd;
    private Long workflowInstanceId;
    private String status;
    private Date createTime;
    private Date updateTime;
}
