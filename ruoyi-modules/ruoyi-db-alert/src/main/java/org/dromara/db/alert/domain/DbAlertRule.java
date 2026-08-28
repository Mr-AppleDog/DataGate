package org.dromara.db.alert.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 告警规则（docs/04 §8.1 + docs/07 §7）。表单 DSL 版本化，修改必须版本化并写审计。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_alert_rule")
public class DbAlertRule extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private String name;

    /**
     * P1/P2/P3/COLLECTOR
     */
    private String severity;

    /**
     * 作用范围 JSON（environment/tags/dataSourceId/database/fingerprint/engine）
     */
    private String scope;

    private String metric;

    /**
     * GE/LE/GT/LT
     */
    private String operator;

    private BigDecimal threshold;

    /**
     * 评估窗口秒数（5 分钟=300）
     */
    private Integer durationSeconds;

    /**
     * 0/1
     */
    private String firstSeenOnly;

    /**
     * 去重抑制窗口（默认 900=15 分钟）
     */
    private Integer dedupWindowSeconds;

    private String silenceConfig;

    private String routing;

    private String configDsl;

    private String status;

    /**
     * 规则版本（修改版本化，兼作乐观锁）
     */
    @Version
    private Integer version;

    @TableLogic
    private String delFlag;
}
