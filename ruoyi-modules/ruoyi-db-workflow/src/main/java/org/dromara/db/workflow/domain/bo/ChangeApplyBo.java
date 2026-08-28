package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 变更申请入参（docs/03 §10.3、docs/05 §2.8，M5-02）。
 *
 * <p>申请人提交 DML/DDL + 两级审批人（业务负责人/DBA）+ 回滚方案 + 影响说明。
 *
 * @author DataGate
 */
@Data
public class ChangeApplyBo {

    @NotNull
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;

    /** DML/DDL */
    @NotBlank
    private String changeType;

    /** DML/DDL SQL（precheck 后以密文锁定，审批后不可篡改） */
    @NotBlank
    private String statement;

    @NotNull
    private Long bizApproverId;
    @NotNull
    private Long dbaApproverId;

    private String rollbackPlan;
    private String impactSummary;
}
