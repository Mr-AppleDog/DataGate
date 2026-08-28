package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Date;

/**
 * 变更执行窗口入参（docs/03 §10.3、docs/05 §2.8 :schedule，M5-02）。
 *
 * @author DataGate
 */
@Data
public class ChangeScheduleBo {

    @NotNull
    private Long orderId;

    @NotNull
    private Date executionWindowStart;
    @NotNull
    private Date executionWindowEnd;
}
