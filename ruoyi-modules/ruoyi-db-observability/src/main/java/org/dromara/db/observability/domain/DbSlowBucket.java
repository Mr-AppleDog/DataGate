package org.dromara.db.observability.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 慢查询窗口聚合（docs/04 §7.5 + docs/07 §6.2）：5分钟/小时/天。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_slow_bucket")
public class DbSlowBucket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fingerprintId;

    /**
     * FIVE_MIN/HOUR/DAY
     */
    private String granularity;

    private Date bucketStart;

    private Date bucketEnd;

    private Integer eventCount;

    private Integer errorCount;

    private Long durationMin;

    private Long durationMax;

    private Long durationAvg;

    private Long durationP95;

    private Long durationP99;

    private Long totalDuration;

    private Long totalLockWait;

    private Long totalRowsExamined;

    private Long totalRowsReturned;

    private Integer affectedUsers;

    /**
     * 受影响库列表（JSON）
     */
    private String affectedDatabases;

    /**
     * COMPLETE/PARTIAL
     */
    private String completeness;

    private Date firstSeenAt;

    private Date lastSeenAt;

    /**
     * 指标发布标记（已发布给告警评估的桶不重复评估）
     */
    private Date publishedAt;

    @Version
    private Integer version;

    private Date createTime;

    private Date updateTime;
}
