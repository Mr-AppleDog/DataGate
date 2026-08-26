package org.dromara.db.resource.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 凭据创建请求（CRED-001/004：只写，永不回显）。
 *
 * <p>password 字段只在创建时使用一次；Controller 日志/校验异常不得打印请求体。</p>
 *
 * @author DataGate
 */
@Data
public class DbCredentialBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "数据源不能为空")
    private Long dataSourceId;

    @NotBlank(message = "凭据用途不能为空")
    @Pattern(regexp = "QUERY|CHANGE|MONITOR", message = "凭据用途必须为 QUERY/CHANGE/MONITOR")
    private String purpose;

    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 明文密码（只写一次）。序列化输出时遮蔽。
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 防止日志打印密码
     */
    @Override
    public String toString() {
        return "DbCredentialBo(dataSourceId=" + dataSourceId + ", purpose=" + purpose
            + ", username=" + username + ", password=******)";
    }
}
