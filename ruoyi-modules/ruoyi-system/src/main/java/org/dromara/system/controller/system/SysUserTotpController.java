package org.dromara.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.service.ISysUserTotpService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户 TOTP 双因素认证（IAM-005）。
 *
 * <p>setup/confirm 为本人操作（登录态）；adminReset 需管理权限。
 * setup 返回的密钥与恢复码仅本次响应展示，服务端不留存明文。</p>
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/totp")
public class SysUserTotpController extends BaseController {

    private final ISysUserTotpService totpService;

    /**
     * 发起 TOTP 绑定（返回 Base32 密钥、otpauth URL、恢复码——仅此一次）
     */
    @PostMapping("/setup")
    public R<ISysUserTotpService.TotpSetupResult> setup() {
        return R.ok(totpService.setup());
    }

    /**
     * 确认绑定（校验 TOTP 码后转 ACTIVE）
     */
    @PostMapping("/confirm")
    public R<Void> confirm(@Validated @RequestBody TotpCodeBody body) {
        totpService.confirm(body.code());
        return R.ok();
    }

    /**
     * 当前用户 TOTP 状态
     */
    @GetMapping("/status")
    public R<ISysUserTotpService.TotpStatus> status() {
        return R.ok(totpService.statusOf(LoginHelper.getUserId()));
    }

    /**
     * 本人解绑（需有效验证码）
     */
    @PostMapping("/unbind")
    public R<Void> unbind(@Validated @RequestBody TotpCodeBody body) {
        totpService.unbind(LoginHelper.getUserId(), body.code(), false);
        return R.ok();
    }

    /**
     * 管理员强制解绑（用户丢失设备场景）
     */
    @SaCheckPermission("system:user:edit")
    @PostMapping("/adminReset/{userId}")
    public R<Void> adminReset(@PathVariable @NotNull Long userId) {
        totpService.unbind(userId, null, true);
        return R.ok();
    }

    /**
     * TOTP 验证码请求体
     */
    public record TotpCodeBody(@NotBlank(message = "验证码不能为空") String code) {
    }
}
