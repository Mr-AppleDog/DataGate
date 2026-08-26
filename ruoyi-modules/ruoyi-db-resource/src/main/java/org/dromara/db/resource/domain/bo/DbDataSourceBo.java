package org.dromara.db.resource.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import io.github.linpeilie.annotations.AutoMapping;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.dromara.db.resource.domain.DbDataSource;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 数据源创建/更新请求（RES-002）。
 * 只允许结构化字段；连接参数白名单键值；禁止任何 URL 级拼接。
 *
 * @author DataGate
 */
@Data
@AutoMapper(target = DbDataSource.class, reverseConvertGenerate = false)
public class DbDataSourceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 数据源 ID（更新必填，创建为空）
     */
    private Long id;

    @NotNull(message = "环境不能为空")
    private Long environmentId;

    @NotBlank(message = "数据源类型不能为空")
    @Pattern(regexp = "MYSQL|POSTGRESQL|REDIS|TAIR", message = "不支持的数据源类型")
    private String type;

    @NotBlank(message = "数据源名称不能为空")
    private String name;

    @NotBlank(message = "主机地址不能为空")
    private String host;

    @NotNull(message = "端口不能为空")
    @Min(1)
    @Max(65535)
    private Integer port;

    private String defaultDatabase;

    /**
     * 白名单连接参数（禁止密码类键）。DTO 层只做传输，
     * 序列化与密钥类键校验由服务层 serializeOptions 统一负责并覆盖写入。
     */
    @AutoMapping(target = "connectionOptions", ignore = true)
    private Map<String, String> connectionOptions;

    @Pattern(regexp = "DISABLE|PREFER|REQUIRE|VERIFY_CA|FULL", message = "非法 TLS 模式")
    private String tlsMode;

    private String ownerType;

    private Long ownerId;

    private String remark;

    /**
     * 乐观锁版本（更新必填）
     */
    private Integer version;
}
