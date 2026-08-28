package org.dromara.db.workflow.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 导出工单（docs/04 §5.6 dbg_export_job，EXP-001）。
 *
 * <p>独立 EXPORT 权限+两级审批（申请人→资源 Owner→DBA）；
 * SQL 密文锁定不可篡改；对象随机键+服务端加密；下载票据一次性短时。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_export_job")
public class DbExportJob implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;
    private String requestNo;
    private Long applicantId;
    private Long dataSourceId;
    private String databaseName;
    private String schemaName;

    /** 申请时锁定的 SQL 密文（重新解析+鉴权后冻结） */
    private String statementEncrypted;
    private String statementHash;
    private String fingerprint;

    /** 资源快照 + masking 策略版本（jsonb） */
    private String resourceSnapshot;
    /** 限制（maxRows/maxBytes，jsonb） */
    private String limits;

    private String decisionId;
    /** 锁定的脱敏级别（MASKED/UNMASKED/HIDDEN） */
    private String maskingLevel;

    /** 状态机（docs/05 §4.4） */
    private String status;

    private Long rowCount;
    private Long resultBytes;
    /** 随机对象键（非公开 URL） */
    private String objectKey;
    private String fileHash;
    private String encryptionKeyRef;

    private Integer downloadCount;
    /** 一次性下载票据哈希 */
    private String ticketHash;
    private Date ticketExpiresAt;
    /** 对象生命周期（默认 24h） */
    private Date expiresAt;
    private Date deletedAt;

    private Long workflowInstanceId;
    private Integer version;

    private Long createBy;
    private Date createTime;
    private Long updateBy;
    private Date updateTime;
    private String delFlag;
}
