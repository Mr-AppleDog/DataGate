package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 变更审批/拒绝/撤销入参（docs/03 §10.3，M5-02）。
 *
 * @author DataGate
 */
@Data
public class ChangeApproveBo {

    @NotNull
    private Long orderId;

    private String message;
}
