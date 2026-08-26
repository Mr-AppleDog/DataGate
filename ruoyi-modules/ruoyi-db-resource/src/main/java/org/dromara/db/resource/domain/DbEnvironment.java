package org.dromara.db.resource.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 环境（docs/04 第 3.1 节，RES-001）
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_environment")
public class DbEnvironment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    /**
     * 环境编码（如 prod）
     */
    private String code;

    /**
     * 环境名称
     */
    private String name;

    /**
     * 风险等级：LOW/MEDIUM/HIGH/CRITICAL
     */
    private String riskLevel;

    /**
     * 默认安全策略（查询默认限制）
     */
    private String defaultPolicy;

    /**
     * 状态：ACTIVE/DISABLED
     */
    private String status;

    private String remark;

    @TableLogic
    private String delFlag;
}
