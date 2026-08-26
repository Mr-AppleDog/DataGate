package org.dromara.system.service;

import org.dromara.system.domain.bo.SysUserBo;

/**
 * 用户安全状态服务（IAM-002 首次登录强制改密 / IAM-003 密码策略）。
 *
 * <p>任何设置密码的入口（管理员创建/重置、自助改密、注册、首次改密预认证端点）
 * 都必须经过本服务的策略校验，禁止绕过。</p>
 *
 * @author DataGate
 */
public interface ISysUserSecurityService {

    /**
     * 管理员创建用户：策略校验 + 加密入库 + 标记首次改密（同一事务）。
     *
     * @param user 用户 BO（password 为明文，方法内完成哈希，绝不落盘/入日志）
     * @return 新用户 ID
     */
    Long createUserWithInitialPassword(SysUserBo user);

    /**
     * 管理员重置密码：策略校验 + 加密更新 + 标记首次改密 + 踢下线（同一事务）。
     */
    void resetPasswordByAdmin(SysUserBo user);

    /**
     * 本人修改密码（已登录）：校验旧密码 + 策略校验 + 清除首次改密标记。
     *
     * @param userId      当前登录用户
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文
     */
    void changeOwnPassword(Long userId, String oldPassword, String newPassword);

    /**
     * 首次改密预认证端点（未登录）：旧密码 + TOTP（如已绑定）+ 策略校验 +
     * 更新密码 + 清除标记 + 踢下线。仅在 must_change_pwd=true 时可用。
     */
    void changeInitialPassword(String username, String oldPassword, String newPassword, String mfaCode);

    /**
     * 是否处于必须改密状态（登录链路调用，命中则拒绝发 token）。
     */
    boolean isMustChangePassword(Long userId);

    /**
     * 登录链路断言：处于必须改密状态时审计并抛出
     * {@code IAM_PASSWORD_CHANGE_REQUIRED}（密码/TOTP 校验通过之后调用）。
     */
    void assertPasswordChangeNotRequired(Long userId);
}
