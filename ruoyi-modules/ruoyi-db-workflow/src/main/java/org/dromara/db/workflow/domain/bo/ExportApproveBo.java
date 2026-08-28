package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 导出审批入参（docs/03 §10.2）。
 *
 * @author DataGate
 */
@Data
public class ExportApproveBo {

    @NotNull
    private Long jobId;

    /** 审批意见 */
    private String message;
}
