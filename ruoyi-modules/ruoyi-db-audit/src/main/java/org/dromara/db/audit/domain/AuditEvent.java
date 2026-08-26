package org.dromara.db.audit.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * 不可变审计事件（docs/04 第 6.1 节）。
 * 事实表：不继承 BaseEntity，无逻辑删除，应用层不提供 update/delete（AUD-004）。
 *
 * @author DataGate
 */
@Data
@TableName(value = "dbg_audit_event", autoResultMap = true)
public class AuditEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 雪花 ID（分区表联合主键之一，另一为 occurred_at）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 事件全局唯一 ID（UUID）
     */
    private String eventId;

    /**
     * 审计类别
     */
    private String category;

    /**
     * 规范动作
     */
    private String action;

    /**
     * 操作人（系统任务为 null）
     */
    private Long actorId;

    /**
     * 操作人快照
     */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> actorSnapshot;

    /**
     * 目标类型
     */
    private String targetType;

    /**
     * 目标 ID
     */
    private String targetId;

    /**
     * 遮蔽后的目标快照
     */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> targetSnapshot;

    /**
     * 结果：SUCCESS/FAILURE/DENIED/UNKNOWN
     */
    private String result;

    private String sourceIp;

    private String userAgent;

    private String traceId;

    /**
     * 扩展明细（不含秘密与查询结果）
     */
    @TableField(typeHandler = JacksonTypeHandler.class, jdbcType = JdbcType.OTHER)
    private Map<String, Object> details;

    /**
     * 发生时间（UTC）
     */
    private Instant occurredAt;

    /**
     * 保留类别：ONE_YEAR/THREE_YEARS
     */
    private String retentionClass;

    /**
     * 哈希链分片键（UTC 日，yyyyMMdd）
     */
    private String chainKey;

    /**
     * 同分片前一事件哈希
     */
    private String previousHash;

    /**
     * 本事件哈希
     */
    private String eventHash;
}
