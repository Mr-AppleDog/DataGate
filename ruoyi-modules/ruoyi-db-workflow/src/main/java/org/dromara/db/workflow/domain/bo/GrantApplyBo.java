package org.dromara.db.workflow.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.SubjectType;

import java.time.Instant;
import java.util.Map;

/**
 * 查询权限申请入参（docs/03 §10.1，M2-02）。
 *
 * <p>申请人提交申请，指定目标审批人（资源 Owner/DBA，docs/03 §9）。
 * 申请人不能审批本人申请（docs/03 §13 #9，服务端强制）。</p>
 *
 * @author DataGate
 */
@Data
public class GrantApplyBo {

    /** 目标审批人 userId（资源 Owner 或 DBA） */
    @NotNull
    private Long approverId;

    /** 授权主体类型/ID（获权对象，可与申请人不同——代他人申请） */
    @NotNull
    private SubjectType subjectType;
    @NotNull
    private Long subjectId;

    /** 目标资源/动作/效果 */
    @NotNull
    private Long resourceId;
    @NotNull
    private DbAction action;
    @NotNull
    private GrantEffect effect;

    /** 标准条件（docs/03 §6，不含秘密） */
    private Map<String, Object> conditions;

    /** 生效/截止（生产单次最长 30 天，docs/03 §10.1） */
    private Instant effectiveAt;
    @NotNull
    private Instant expiresAt;

    /** 申请理由（不含秘密） */
    @NotNull
    private String reason;
}
