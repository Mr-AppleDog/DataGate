package org.dromara.db.resource.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.db.resource.domain.DbEnvironment;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 环境视图对象（RES-001）
 *
 * @author DataGate
 */
@Data
@AutoMapper(target = DbEnvironment.class)
public class DbEnvironmentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String code;

    private String name;

    /**
     * 风险等级：LOW/MEDIUM/HIGH/CRITICAL
     */
    private String riskLevel;

    /**
     * 默认安全策略（JSON）
     */
    private String defaultPolicy;

    private String status;

    private String remark;

    private Date createTime;
}
