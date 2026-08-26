package org.dromara.db.resource.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 元数据同步任务（docs/04 第 3.8 节，RES-005）。错误详情经秘密遮蔽。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_metadata_sync_job")
public class DbMetadataSyncJob implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dataSourceId;

    /**
     * MANUAL/SCHEDULED
     */
    private String triggerType;

    /**
     * RUNNING/SUCCESS/FAILED
     */
    private String status;

    private Long metadataVersion;

    private Date startedAt;

    private Date finishedAt;

    private Integer foundCount;

    private Integer updatedCount;

    private Integer droppedCount;

    /**
     * 平台标准错误码
     */
    private String errorCode;

    /**
     * 错误摘要（不含秘密/堆栈）
     */
    private String errorSummary;

    private Long createBy;

    private Date createTime;
}
