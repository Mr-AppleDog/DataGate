package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 审批入参（批准/拒绝/撤销，M2-02）。
 *
 * @author DataGate
 */
@Data
public class GrantApproveBo {

    /** 申请单 ID */
    @NotNull
    private Long applicationId;

    /** 办理意见（不含秘密） */
    private String message;
}
