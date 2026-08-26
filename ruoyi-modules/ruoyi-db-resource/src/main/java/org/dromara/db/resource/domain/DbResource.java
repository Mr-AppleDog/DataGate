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
 * 统一资源目录（docs/04 第 3.6 节）。授权始终引用 resource id。
 * 注意：本表无 del_flag/create_by 等上游基字段，不使用 BaseEntity。
 *
 * @author DataGate
 */
@Getter
@Setter
@TableName("dbg_resource")
public class DbResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long dataSourceId;

    /**
     * 根资源（数据库）为 0
     */
    private Long parentId;

    /**
     * DATABASE/SCHEMA/TABLE/VIEW/COLUMN/REDIS_DB/KEY_PREFIX_POLICY
     */
    private String resourceType;

    private String physicalName;

    private String normalizedName;

    private String canonicalPath;

    /**
     * 非秘密元数据（JSON）
     */
    private String metadata;

    /**
     * ACTIVE/DISABLED/DROPPED/UNKNOWN
     */
    private String status;

    private Long metadataVersion;

    private Date firstSeenAt;

    private Date lastSeenAt;

    private Date createTime;

    private Date updateTime;
}
