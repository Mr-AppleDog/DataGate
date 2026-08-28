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
 * 变更执行尝试（docs/04 §5.7 dbg_change_execution）。
 *
 * <p>每次尝试记录执行节点/凭据/状态/影响行数/遮蔽错误/逐语句结果。禁止失败后自动重放非幂等变更。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_change_execution")
public class DbChangeExecution implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    private Integer attemptNo;
    private String executionNode;
    private Long credentialId;

    private Date startedAt;
    private Date finishedAt;
    /** SUCCEEDED/FAILED/UNKNOWN */
    private String status;
    private Long affectedRows;
    private String errorCode;
    private String errorSummary;
    /** 逐语句结果 JSON */
    private String statementResults;
    /** 幂等键（用户+动作+摘要绑定，docs/08 §10） */
    private String idempotencyKey;
}
