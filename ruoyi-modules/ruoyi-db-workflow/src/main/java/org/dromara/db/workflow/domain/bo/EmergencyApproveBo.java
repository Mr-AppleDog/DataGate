package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 紧急访问审批/撤销/复盘入参（docs/03 §10.4）。
 *
 * @author DataGate
 */
@Data
public class EmergencyApproveBo {

    @NotNull
    private Long accessId;

    private String message;

    /** 复盘内容（postMortem 用） */
    private String postMortemContent;
}
