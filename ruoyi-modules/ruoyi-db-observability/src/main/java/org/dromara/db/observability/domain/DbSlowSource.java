package org.dromara.db.observability.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 慢查询采集来源（docs/04 §7.1，SLOW）。
 * 采集类型/间隔/状态/监控凭据引用；监控账号独立于查询与变更账号。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_slow_source")
public class DbSlowSource extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long dataSourceId;

    /**
     * MYSQL_SLOW_LOG/MYSQL_PERF_SCHEMA/ALIYUN_API/PG_STAT_STATEMENTS/PG_LOG/REDIS_SLOWLOG
     */
    private String collectType;

    /**
     * 采集间隔（1-15 分钟）
     */
    private Integer collectIntervalSeconds;

    /**
     * ACTIVE/PAUSED/ERROR
     */
    private String status;

    /**
     * 独立监控账号凭据 ID
     */
    private Long monitorCredentialId;

    private Date lastSuccessAt;

    /**
     * 采集滞后秒数
     */
    private Integer lagSeconds;

    /**
     * 连续失败次数（≥3 触发 COLLECTOR 告警）
     */
    private Integer consecutiveFailures;

    /**
     * 平台标准错误码（不含秘密/堆栈）
     */
    private String lastErrorCode;

    /**
     * 错误摘要（秘密遮蔽后，≤500 字符）
     */
    private String lastErrorSummary;

    @TableLogic
    private String delFlag;
}
