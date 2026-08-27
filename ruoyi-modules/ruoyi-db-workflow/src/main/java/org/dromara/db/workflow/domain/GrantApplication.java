package org.dromara.db.workflow.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.SubjectType;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * 查询权限申请单（docs/03 §10.1、docs/04 §4，WF-001，M2-02）。
 *
 * <p>申请人提交申请→审批人审批→批准则回调 {@code GrantApprovalCallbackService} 生成 Grant；
 * 拒绝不生成（docs/10 M2-02）。审批流由 WarmFlow 编排（flow_instance_id 关联）。</p>
 *
 * <p>状态：PENDING/APPROVED/REJECTED/REVOKED。批准后 grant_id 指向生成的授权。</p>
 *
 * @author DataGate
 */
@Data
@TableName(value = "dbg_grant_application", autoResultMap = true)
public class GrantApplication implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 固定系统租户 */
    private String tenantId;

    /** WarmFlow 流程实例 ID（流程启动后回填） */
    private Long flowInstanceId;

    /** 申请人 */
    private Long applicantId;

    /** 审批人（审批后回填） */
    private Long approverId;

    /** 授权主体类型/ID（获权对象，可与申请人不同——代他人申请） */
    private SubjectType subjectType;
    private Long subjectId;

    /** 目标资源/动作/效果 */
    private Long resourceId;
    private DbAction action;
    private GrantEffect effect;

    /** 标准条件（docs/03 §6，不含秘密） */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> conditions;

    /** 生效/截止 */
    private Instant effectiveAt;
    private Instant expiresAt;

    /** 申请理由/拒绝理由（不含秘密） */
    private String reason;

    /** PENDING/APPROVED/REJECTED/REVOKED */
    private String status;

    /** 批准后生成的授权 ID */
    private Long grantId;

    private Long createDept;
    private Long createBy;
    private Instant createTime;
    private Long updateBy;
    private Instant updateTime;
    private String delFlag;
}
