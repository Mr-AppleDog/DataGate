package org.dromara.db.workflow.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 导出工单视图（docs/04 §5.6）。不回传 SQL 密文/对象键明文/票据明文。
 *
 * @author DataGate
 */
@Data
public class DbExportJobVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String requestNo;
    private Long applicantId;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;
    private String fingerprint;
    private String resourceSnapshot;
    private String limits;
    private String maskingLevel;
    private String status;
    private Long rowCount;
    private Long resultBytes;
    private Integer downloadCount;
    private Date expiresAt;
    private Date deletedAt;
    private Long workflowInstanceId;
    private Date createTime;
    private Date updateTime;
}
