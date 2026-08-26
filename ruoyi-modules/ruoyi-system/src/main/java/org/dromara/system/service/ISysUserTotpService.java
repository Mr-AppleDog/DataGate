package org.dromara.system.service;

import java.util.List;

/**
 * 用户 TOTP 服务（IAM-005，docs/08 第 5 节）。
 *
 * <p>铁律：TOTP 密钥密文只写不回显；setup 返回的 Base32 密钥与恢复码仅展示一次；
 * 绑定/确认/解绑/恢复码使用均写安全审计。</p>
 *
 * @author DataGate
 */
public interface ISysUserTotpService {

    /**
     * 为当前用户生成 TOTP 密钥（PENDING，重新生成将覆盖未确认记录）
     *
     * @return Base32 密钥、otpauth URL、一次性恢复码明文
     */
    TotpSetupResult setup();

    /**
     * 确认绑定：校验当前用户 PENDING 密钥的 TOTP 码，成功则转 ACTIVE
     *
     * @param code 6 位验证码
     */
    void confirm(String code);

    /**
     * 当前用户 TOTP 状态（bound=已绑定 ACTIVE, pending=待确认）
     */
    TotpStatus statusOf(Long userId);

    /**
     * 解绑（本人凭有效验证码，或管理员强制解绑）
     *
     * @param userId  目标用户
     * @param code    本人操作时的有效验证码（管理员强制解绑可为 null）
     * @param byAdmin 是否管理员操作
     */
    void unbind(Long userId, String code, boolean byAdmin);

    /**
     * 登录阶段校验：用户若已绑定 ACTIVE TOTP，必须提供有效验证码或恢复码。
     * 未绑定直接放行；校验失败抛出 IAM_MFA_REQUIRED。
     * 防重放：同一时间步的码不可复用；恢复码用后销毁。
     *
     * @param userId 用户 ID
     * @param code   登录请求携带的 TOTP 码或恢复码（可空）
     */
    void assertLoginAllowed(Long userId, String code);

    /**
     * TOTP 设置结果（敏感：仅 setup 时返回一次）
     *
     * @param secretBase32  Base32 编码密钥（仅展示一次）
     * @param otpauthUrl    otpauth:// 链接（可生成二维码）
     * @param recoveryCodes 恢复码明文（仅展示一次）
     */
    record TotpSetupResult(String secretBase32, String otpauthUrl, List<String> recoveryCodes) {
    }

    /**
     * TOTP 状态
     *
     * @param bound   已绑定（ACTIVE）
     * @param pending 待确认（PENDING）
     */
    record TotpStatus(boolean bound, boolean pending) {
    }
}
