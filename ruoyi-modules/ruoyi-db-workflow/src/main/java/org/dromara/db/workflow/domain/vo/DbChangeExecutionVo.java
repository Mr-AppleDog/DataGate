package org.dromara.db.workflow.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 变更执行尝试视图（docs/04 §5.7）。
 *
 * @author DataGate
 */
@Data
public class DbChangeExecutionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long orderId;
    private Integer attemptNo;
    private String executionNode;
    private Long credentialId;
    private Date startedAt;
    private Date finishedAt;
    private String status;
    private Long affectedRows;
    private String errorCode;
    private String errorSummary;
    private String statementResults;
}
