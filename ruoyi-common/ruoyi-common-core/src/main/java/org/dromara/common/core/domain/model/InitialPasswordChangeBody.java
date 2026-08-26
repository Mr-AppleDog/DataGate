package org.dromara.common.core.domain.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 首次登录修改初始密码请求体（DataGate M1-01，IAM-002）。
 *
 * <p>敏感 DTO：不使用 {@code @Data}/{@code @ToString}，密码字段绝不进入 toString。</p>
 *
 * @author DataGate
 */
@Getter
@Setter
public class InitialPasswordChangeBody {

    /**
     * 用户名
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 30, message = "用户名长度须在 2-30 之间")
    private String username;

    /**
     * 旧密码（初始密码）
     */
    @NotBlank(message = "旧密码不能为空")
    @Size(max = 64, message = "旧密码长度超出限制")
    private String oldPassword;

    /**
     * 新密码（策略校验在服务层，IAM-003）
     */
    @NotBlank(message = "新密码不能为空")
    @Size(max = 64, message = "新密码长度须在 64 位以内")
    private String newPassword;

    /**
     * TOTP 验证码或恢复码（已绑定用户必填）
     */
    private String mfaCode;
}
