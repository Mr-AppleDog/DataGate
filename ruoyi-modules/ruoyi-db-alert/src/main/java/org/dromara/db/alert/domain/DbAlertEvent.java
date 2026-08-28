package org.dromara.db.alert.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 告警事件（docs/04 §8.2 + docs/07 §8）。去重键抑制轰炸；状态机乐观锁。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_alert_event")
public class DbAlertEvent extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long ruleId;

    /**
     * ruleId + sourceId + fingerprint + window
     */
    private String dedupKey;

    private Long dataSourceId;

    private Long fingerprintId;

    private String fingerprint;

    private String severity;

    /**
     * PENDING/FIRING/ACKNOWLEDGED/RESOLVED/SILENCED
     */
    private String status;

    private Date firstFiredAt;

    private Date lastFiredAt;

    private Integer triggerCount;

    private BigDecimal currentValue;

    private BigDecimal threshold;

    private Date windowStart;

    private Date windowEnd;

    private Date resolvedAt;

    private Long assigneeId;

    private Date silenceUntil;

    private String evidenceSummary;

    @Version
    private Integer version;
}
