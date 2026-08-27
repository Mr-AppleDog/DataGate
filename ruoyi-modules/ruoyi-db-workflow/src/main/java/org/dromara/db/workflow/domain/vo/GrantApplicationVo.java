package org.dromara.db.workflow.domain.vo;

import lombok.Data;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.SubjectType;

import java.time.Instant;
import java.util.Map;

/**
 * 查询权限申请单视图（M2-02）。
 *
 * @author DataGate
 */
@Data
public class GrantApplicationVo {

    private Long id;
    private Long flowInstanceId;
    private Long applicantId;
    private Long approverId;
    private SubjectType subjectType;
    private Long subjectId;
    private Long resourceId;
    private DbAction action;
    private GrantEffect effect;
    private Map<String, Object> conditions;
    private Instant effectiveAt;
    private Instant expiresAt;
    private String reason;
    private String status;
    private Long grantId;
    private Instant createTime;
}
