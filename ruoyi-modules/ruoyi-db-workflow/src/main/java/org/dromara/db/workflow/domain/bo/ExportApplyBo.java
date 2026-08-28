package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 导出申请入参（docs/03 §10.2、docs/05 §2.7）。
 *
 * <p>申请人提交：目标数据源 + 锁定 SQL + 两级审批人（资源 Owner/DBA）+ 限制 + 理由。
 * 创建时重新解析+重新鉴权+锁定策略版本（docs/06 §12）。申请人不能审批本人（docs/03 §9）。</p>
 *
 * @author DataGate
 */
@Data
public class ExportApplyBo {

    @NotNull
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;

    /** 申请导出的 SQL（创建时解析鉴权后以密文锁定，不可篡改） */
    @NotBlank
    private String statement;

    /** 资源负责人审批人 userId */
    @NotNull
    private Long ownerApproverId;
    /** DBA 审批人 userId */
    @NotNull
    private Long dbaApproverId;

    /** 导出限制 */
    private Long maxRows;
    private Long maxBytes;

    @NotBlank
    private String reason;
}
