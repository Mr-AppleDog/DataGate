package org.dromara.db.resource.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.db.resource.domain.DbDataSource;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 数据源视图对象（RES-002）。只含非秘密结构化配置，绝不包含凭据内容。
 *
 * @author DataGate
 */
@Data
@AutoMapper(target = DbDataSource.class)
public class DbDataSourceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private Long environmentId;

    private String type;

    private String name;

    private String host;

    private Integer port;

    private String defaultDatabase;

    /**
     * 白名单连接参数（JSON，已通过服务端密钥类键过滤）
     */
    private String connectionOptions;

    private String tlsMode;

    /**
     * DRAFT/VERIFYING/ACTIVE/DISABLED/ERROR/ARCHIVED
     */
    private String status;

    private String ownerType;

    private Long ownerId;

    private Date lastVerifiedAt;

    /**
     * 平台标准错误码（不含异常细节）
     */
    private String lastErrorCode;

    private String remark;

    /**
     * 乐观锁版本（更新时回传）
     */
    private Integer version;

    private Date createTime;
}
