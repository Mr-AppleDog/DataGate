package org.dromara.db.resource.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 数据源（docs/04 第 3.2 节，RES-002）。
 * 结构化字段保存连接信息；密码等秘密永远存凭据保险箱，不进本表。
 *
 * @author DataGate
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dbg_data_source")
public class DbDataSource extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId
    private Long id;

    private Long environmentId;

    /**
     * MYSQL/POSTGRESQL/REDIS/TAIR
     */
    private String type;

    private String name;

    private String host;

    private Integer port;

    private String defaultDatabase;

    /**
     * 白名单连接参数（禁止密码类键与任意 URL）
     */
    private String connectionOptions;

    /**
     * DISABLE/PREFER/REQUIRE/VERIFY_CA/FULL
     */
    private String tlsMode;

    /**
     * DRAFT/VERIFYING/ACTIVE/DISABLED/ERROR/ARCHIVED
     */
    private String status;

    private String ownerType;

    private Long ownerId;

    /**
     * 权限缓存版本（docs/02 第 9.2 节）
     */
    private Long policyVersion;

    private Date lastVerifiedAt;

    /**
     * 平台标准错误码，不保存秘密或完整异常
     */
    private String lastErrorCode;

    private String remark;

    /**
     * 乐观锁
     */
    @Version
    private Integer version;

    @TableLogic
    private String delFlag;
}
