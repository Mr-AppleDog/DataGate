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
 * 列敏感策略（docs/04 §3.7 dbg_column_profile）。
 *
 * <p>resource_id 即 dbg_resource.id（type=COLUMN），1:1 关联，不自增。
 * 元数据重新同步不得覆盖 classification_source=MANUAL 的标签（docs/04 §3.7、docs/10 M5-05）。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_column_profile")
public class DbColumnProfile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 列资源 ID（dbg_resource.id，type=COLUMN）；非自增 */
    @TableId(type = IdType.INPUT)
    private Long resourceId;

    /** PUBLIC/INTERNAL/SENSITIVE/RESTRICTED */
    private String sensitivityLevel;

    /** PHONE/ID_CARD/BANK_CARD/EMAIL/ADDRESS/CUSTOM/NONE */
    private String maskingType;

    /** 自定义掩码配置 JSON（keepPrefix/keepSuffix/maskChar） */
    private String maskingConfig;

    /** MANUAL/RULE/IMPORT；MANUAL 不被重同步覆盖 */
    private String classificationSource;

    /** 人工确认人（MANUAL 时必填） */
    private Long confirmedBy;

    /** 人工确认时间 */
    private Date confirmedAt;
}
