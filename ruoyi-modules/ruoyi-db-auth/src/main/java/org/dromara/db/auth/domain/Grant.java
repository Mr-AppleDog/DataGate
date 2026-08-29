package org.dromara.db.auth.domain;

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
 * 资源授权（docs/04 第 4.2 节，docs/03 第 2.2 节，AUTH-001~004）。
 *
 * <p>配置型实体：授权创建与撤销由审批流（db-workflow，M2-02）负责；本切片的鉴权引擎只读取候选授权做判定，
 * 不提供创建/撤销入口。effect=ALLOW/DENY、显式拒绝优先；默认拒绝。</p>
 *
 * <p>枚举列（subject_type/action/effect/source_type）以枚举名（name）写入 varchar 列，
 * 由 MyBatis 默认 {@code EnumTypeHandler} 处理（与 db-core 冻结枚举一致，不添加 @EnumValue）。
 * {@code conditions} 为 jsonb，经 {@link JacksonTypeHandler} 与 {@code Map<String,Object>} 互转。</p>
 *
 * <p>本切片偏差（相对 docs/04 第 4.2/4.3 节，已在报告中记录）：</p>
 * <ul>
 *   <li>{@code action} 为单列（每条授权一个动作）；docs/04 第 4.3 节的独立 {@code dbg_grant_action}
 *       多动作表留待后续切片。</li>
 *   <li>以 {@code revoked_at} 时间戳标记撤销，不引入 docs/04 第 4.2 节的 {@code status} 状态机
 *       （SUSPENDED 等）。</li>
 * </ul>
 *
 * @author DataGate
 */
@Data
@TableName(value = "dbg_resource_grant", autoResultMap = true)
public class Grant implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 雪花 ID（主键）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 固定系统租户（docs/04 第 1.2 节；不参与外部数据源授权）
     */
    private String tenantId;

    /**
     * 授权主体类型（USER/DEPT/GROUP/ROLE，docs/03 第 5.2 节）
     */
    private SubjectType subjectType;

    /**
     * 主体 ID
     */
    private Long subjectId;

    /**
     * 目标资源 ID。RESOURCE 范围必须引用不可变 resource_id；GLOBAL 范围为 null。
     */
    private Long resourceId;

    /**
     * 授权资源范围：RESOURCE 为指定资源及其后代，GLOBAL 为租户内全部数据库资源。
     */
    private String scopeType;

    /**
     * 资源动作（docs/03 第 4 节；动作不自动包含其他动作）
     */
    private DbAction action;

    /**
     * 授权效果（ALLOW/DENY，显式拒绝优先）
     */
    private GrantEffect effect;

    /**
     * 标准条件结构（docs/03 第 6 节：sourceIpCidr/timeWindow/maxRows/maxBytes/maxExecutionSeconds/
     * maskingLevel/requireMfa/requireRecentReauth）；不含秘密。
     */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> conditions;

    /**
     * 生效时间（null 表示已生效）
     */
    private Instant effectiveAt;

    /**
     * 截止时间（null 表示永不过期；生产非空由授权创建服务按环境强制）
     */
    private Instant expiresAt;

    /**
     * 撤销时间（null 表示未撤销；非空即失效）
     */
    private Instant revokedAt;

    /**
     * 授权来源（MANUAL/REQUEST/SYSTEM/EMERGENCY，docs/03 第 2.2 节）
     */
    private GrantSourceType sourceType;

    /**
     * 来源 ID（工单或来源对象 ID；用于审批回调幂等）
     */
    private Long sourceId;

    /**
     * 授权原因（不含秘密）
     */
    private String reason;

    /**
     * 策略版本（创建时快照；缓存键含此版本，docs/03 第 8 节）
     */
    private Long policyVersion;

    private Long createDept;

    private Long createBy;

    private Instant createTime;

    private Long updateBy;

    private Instant updateTime;

    /**
     * 逻辑删除标志（与 V4/V5 配置型表一致；授权撤销不逻辑删除，写 revoked_at）
     */
    private String delFlag;
}
