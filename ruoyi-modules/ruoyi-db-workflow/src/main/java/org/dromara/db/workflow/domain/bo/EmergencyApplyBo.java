package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 紧急访问申请入参（docs/03 §10.4，M5-04）。
 *
 * <p>申请人提交：事件编号 + 目标资源/动作 + 两名不同审批人 + 理由 + 有效期（≤2h）。
 * 强制 TOTP（grant 条件 requireMfa）；申请人不能为任一审批人。
 *
 * @author DataGate
 */
@Data
public class EmergencyApplyBo {

    /** 事件编号（必填） */
    @NotBlank
    private String eventNo;

    @NotNull
    private Long targetResourceId;
    @NotBlank
    private String targetAction;

    @NotNull
    private Long approver1Id;
    @NotNull
    private Long approver2Id;

    /** 有效小时数（≤2，超出按 2h 截断） */
    private Integer validHours;

    @NotBlank
    private String reason;
}
