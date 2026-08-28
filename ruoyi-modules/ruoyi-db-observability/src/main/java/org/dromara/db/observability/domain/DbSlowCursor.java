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
 * 慢查询采集游标（docs/04 §7.2）。乐观锁；重启/轮转/重置可检测。
 * 本表无 create_by 等基字段，不使用 BaseEntity。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_slow_cursor")
public class DbSlowCursor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long slowSourceId;

    /**
     * 分区键（同源多分片，如 PG dbid、Redis 实例纪元）
     */
    private String partitionKey;

    /**
     * 游标 JSON（结构由采集器定义，含上游位置与快照基线）
     */
    private String cursor;

    private Date lastRecordTime;

    private Date lastSuccessAt;

    private Integer consecutiveFailures;

    /**
     * 乐观锁版本（COLLECTOR_CURSOR_CONFLICT）
     */
    @Version
    private Long version;

    private Date createTime;

    private Date updateTime;
}
