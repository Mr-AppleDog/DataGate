package org.dromara.db.resource.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.Map;

/**
 * 临时连接测试请求（RES-004）。
 *
 * <p>不使用 Lombok {@code @Data}，避免秘密字段进入自动生成的 toString；password
 * 在测试结束后由服务主动清零，且不得持久化、审计或回显。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
public class DbConnectionTestBo {

    @NotBlank(message = "数据源类型不能为空")
    @Pattern(regexp = "MYSQL|POSTGRESQL|REDIS|TAIR", message = "不支持的数据源类型")
    private String type;

    @NotBlank(message = "主机地址不能为空")
    @Size(max = 255)
    private String host;

    @NotNull(message = "端口不能为空")
    @Min(1)
    @Max(65535)
    private Integer port;

    @Size(max = 128)
    private String defaultDatabase;

    private Map<String, String> connectionOptions;

    @Pattern(regexp = "DISABLE|PREFER|REQUIRE|VERIFY_CA|FULL", message = "非法 TLS 模式")
    private String tlsMode;

    @NotBlank(message = "测试用户名不能为空")
    @Size(max = 128)
    private String username;

    @NotNull(message = "测试密码不能为空")
    @Size(min = 1, max = 4096, message = "测试密码长度非法")
    private char[] password;

    /**
     * 清除请求对象中仍持有的临时密码。
     */
    public void clearPassword() {
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
    }
}
