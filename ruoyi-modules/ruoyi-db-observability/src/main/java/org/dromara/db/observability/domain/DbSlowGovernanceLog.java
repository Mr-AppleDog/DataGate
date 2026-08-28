package org.dromara.db.observability.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 慢查询治理日志（docs/04 §7.6 + docs/07 §10）：状态/负责人/评论/验证窗口/前后指标，全部追加写不可覆盖。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_slow_governance_log")
public class DbSlowGovernanceLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fingerprintId;

    /**
     * STATUS_CHANGE/ASSIGN/COMMENT/OPTIMIZE_NOTE/VERIFY_WINDOW/METRICS_BEFORE/METRICS_AFTER/REOPEN
     */
    private String action;

    private String fromStatus;

    private String toStatus;

    private Long oldAssigneeId;

    private Long newAssigneeId;

    private String comment;

    private Date dueAt;

    /**
     * 前后指标快照（JSON，含阈值/趋势/证据摘要）
     */
    private String metrics;

    private Long relatedChangeId;

    private Long operatorId;

    private Date createTime;
}
