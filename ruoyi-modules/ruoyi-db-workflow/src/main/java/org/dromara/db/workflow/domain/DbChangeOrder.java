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
 * SQL 变更工单（docs/04 §5.7 dbg_change_order，CHG-001）。
 *
 * <p>不可变 SQL 快照 + 两级审批（业务负责人→DBA）+ 执行窗口 + 专用变更账号。
 * SQL 内容变化后回 DRAFT 清空审批结论（docs/05 §4.5）。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_change_order")
public class DbChangeOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;
    private String requestNo;
    private Long applicantId;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;

    /** DML/DDL */
    private String changeType;

    /** 不可变 SQL 快照密文（审批后不可篡改） */
    private String statementEncrypted;
    private String statementHash;
    private String fingerprint;

    /** 资源快照 + 审批人（jsonb） */
    private String resourceSnapshot;
    /** 预检查结果 JSON（risks/severity） */
    private String precheckResult;
    private String rollbackPlan;
    private String impactSummary;

    private Date executionWindowStart;
    private Date executionWindowEnd;

    private Long workflowInstanceId;
    private String status;
    private Integer version;

    private Long createBy;
    private Date createTime;
    private Long updateBy;
    private Date updateTime;
    private String delFlag;
}
