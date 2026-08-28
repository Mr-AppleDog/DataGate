package org.dromara.db.resource.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 列敏感策略视图（docs/04 §3.7）。
 *
 * @author DataGate
 */
@Data
public class DbColumnProfileVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long resourceId;
    private Long dataSourceId;
    private String canonicalPath;
    private String columnName;
    private String sensitivityLevel;
    private String maskingType;
    private String maskingConfig;
    private String classificationSource;
    private Long confirmedBy;
    private Date confirmedAt;
}
