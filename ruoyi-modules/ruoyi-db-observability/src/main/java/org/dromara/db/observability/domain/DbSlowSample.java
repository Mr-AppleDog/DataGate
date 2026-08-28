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
 * 慢查询逐次样例（docs/04 §7.4 + docs/07 §3 SlowEvent）。
 * 事实表禁止逻辑覆盖历史；保留 30 天，分区化见 ADR-009。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_slow_sample")
public class DbSlowSample implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long slowSourceId;

    private Long fingerprintId;

    /**
     * 来源唯一键（采集幂等）
     */
    private String sourceKey;

    private String sourceEventId;

    /**
     * 数据库事件时间（UTC 存储）
     */
    private Date occurredAt;

    private Date collectedAt;

    private String databaseName;

    /**
     * 执行耗时微秒（核心必填，缺失值 NULL 不用 0 冒充）
     */
    private Long durationMicros;

    private Long lockWaitMicros;

    private Long rowsExamined;

    private Long rowsReturned;

    private Long affectedRows;

    private Long cpuMicros;

    private Long ioBytes;

    private Long tempBytes;

    private String clientAddress;

    private String dbUser;

    private String applicationName;

    /**
     * 脱敏后样例（明文可存）
     */
    private String sanitizedSample;

    /**
     * 原 SQL 单独数据密钥加密（仅 SLOW_SQL_RAW_VIEW + 二次认证可看）
     */
    private String rawSqlEncrypted;

    /**
     * MASKED/RAW_VIEW
     */
    private String rawAccessLevel;

    private Integer sampleRate;

    /**
     * COMPLETE/PARTIAL/ESTIMATED/AGGREGATED/PARSE_FAILED
     */
    private String ingestQuality;

    private Date createTime;
}
