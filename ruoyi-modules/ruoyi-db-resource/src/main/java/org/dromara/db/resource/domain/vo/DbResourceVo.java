package org.dromara.db.resource.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.db.resource.domain.DbResource;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 资源目录视图对象（只含非秘密元数据）
 *
 * @author DataGate
 */
@Data
@AutoMapper(target = DbResource.class)
public class DbResourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long dataSourceId;

    private Long parentId;

    private String resourceType;

    private String physicalName;

    private String canonicalPath;

    /**
     * 非秘密元数据（JSON）
     */
    private String metadata;

    private String status;

    private Long metadataVersion;

    private Date lastSeenAt;
}
