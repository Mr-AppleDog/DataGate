package org.dromara.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.system.domain.DbUserSecurity;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.mapper.SysUserSecurityMapper;
import org.dromara.system.service.ISysUserSecurityService;
import org.dromara.system.service.ISysUserService;
import org.dromara.system.service.ISysUserTotpService;
import org.dromara.system.support.PasswordPolicyValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;

/**
 * 用户安全状态实现。
 *
 * <p>安全约束：方法入参中的明文密码绝不进入日志/审计/异常 message；
 * 审计 details 只记录规则名与原因码。所有密码写入路径同事务维护
 * must_change_pwd 标记，保证「标记失败 = 用户创建失败」（失败关闭）。</p>
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class SysUserSecurityServiceImpl implements ISysUserSecurityService {

    private final SysUserSecurityMapper securityMapper;
    private final SysUserMapper userMapper;
    private final ISysUserService userService;
    private final ISysUserTotpService totpService;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUserWithInitialPassword(SysUserBo user) {
        // IAM-003：策略校验（明文仅存在于此方法栈内）
        PasswordPolicyValidator.validate(user.getPassword(),
            user.getUserName(), user.getPhonenumber(), user.getEmail());
        user.setPassword(BCrypt.hashpw(user.getPassword()));
        userService.insertUser(user);
        markMustChange(user.getUserId());
        audit(AuditCategory.SECURITY, "USER_CREATE", user.getUserId(),
            AuditResult.SUCCESS, Map.of("mustChangePwd", true));
        return user.getUserId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPasswordByAdmin(SysUserBo user) {
        SysUserVo target = userMapper.selectVoById(user.getUserId());
        if (target == null) {
            throw new ServiceException("用户不存在");
        }
        PasswordPolicyValidator.validate(user.getPassword(),
            target.getUserName(), target.getPhonenumber(), target.getEmail());
        int rows = userService.resetUserPwd(user.getUserId(), BCrypt.hashpw(user.getPassword()));
        if (rows < 1) {
            throw new ServiceException("重置密码失败");
        }
        markMustChange(user.getUserId());
        // 重置后旧会话全部失效，用户重新登录时将被引导改密
        kickOffline(user.getUserId());
        audit(AuditCategory.SECURITY, "PASSWORD_RESET_ADMIN", user.getUserId(), AuditResult.SUCCESS, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(Long userId, String oldPassword, String newPassword) {
        SysUserVo user = userMapper.selectVoById(userId);
        if (user == null) {
            throw new ServiceException("用户不存在");
        }
        if (StringUtils.isBlank(oldPassword) || !BCrypt.checkpw(oldPassword, user.getPassword())) {
            audit(AuditCategory.SECURITY, "PASSWORD_CHANGE", userId, AuditResult.DENIED,
                Map.of("reason", "bad_old_password"));
            throw new ServiceException("修改密码失败，旧密码错误");
        }
        if (BCrypt.checkpw(newPassword, user.getPassword())) {
            throw new ServiceException("新密码不能与旧密码相同");
        }
        try {
            PasswordPolicyValidator.validate(newPassword,
                user.getUserName(), user.getPhonenumber(), user.getEmail());
        } catch (DbServiceException e) {
            audit(AuditCategory.SECURITY, "PASSWORD_CHANGE", userId, AuditResult.DENIED,
                Map.of("reason", "policy_violation"));
            throw e;
        }
        userService.resetUserPwd(userId, BCrypt.hashpw(newPassword));
        clearMustChange(userId);
        audit(AuditCategory.SECURITY, "PASSWORD_CHANGE", userId, AuditResult.SUCCESS,
            Map.of("via", "profile"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeInitialPassword(String username, String oldPassword, String newPassword, String mfaCode) {
        SysUserVo user = userMapper.selectVoOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUserName, username));
        // 统一模糊失败，不暴露账号是否存在/状态
        if (user == null || StringUtils.isBlank(oldPassword)
            || !BCrypt.checkpw(oldPassword, user.getPassword())) {
            auditAnonymous("PASSWORD_CHANGE_INITIAL", username, AuditResult.DENIED,
                Map.of("reason", "bad_credentials"));
            throw new ServiceException("用户名或旧密码错误");
        }
        if (!isMustChangePassword(user.getUserId())) {
            throw new ServiceException("当前账号无需通过此入口修改密码，请登录后在个人中心修改");
        }
        // 已绑定 TOTP 的用户必须同时通过双因素校验（防初始口令泄露后被抢改）
        totpService.assertLoginAllowed(user.getUserId(), mfaCode);
        if (BCrypt.checkpw(newPassword, user.getPassword())) {
            throw new ServiceException("新密码不能与旧密码相同");
        }
        try {
            PasswordPolicyValidator.validate(newPassword,
                user.getUserName(), user.getPhonenumber(), user.getEmail());
        } catch (DbServiceException e) {
            auditAnonymous("PASSWORD_CHANGE_INITIAL", username, AuditResult.DENIED,
                Map.of("reason", "policy_violation"));
            throw e;
        }
        // 预认证改密路径无 Sa-Token 登录会话；resetUserPwd 走 SysUserMapper.update 时会被
        // 数据权限切面（PlusDataPermissionHandler.getSqlSegment）拦截并调用 LoginHelper.getLoginUser()
        // 获取当前用户用于行级过滤 → 预认证上下文抛 NotLoginException(TOKEN_FROZEN) 导致改密失败。
        // 改密是系统级操作，不应受数据权限过滤；用 DataPermissionHelper.ignore 显式跳过（DataGate M1-01，IAM-002）。
        DataPermissionHelper.ignore(() -> userService.resetUserPwd(user.getUserId(), BCrypt.hashpw(newPassword)));
        clearMustChange(user.getUserId());
        kickOffline(user.getUserId());
        audit(AuditCategory.SECURITY, "PASSWORD_CHANGE_INITIAL", user.getUserId(), AuditResult.SUCCESS, null);
    }

    @Override
    public boolean isMustChangePassword(Long userId) {
        if (userId == null) {
            return false;
        }
        DbUserSecurity entity = securityMapper.selectById(userId);
        return entity != null && Boolean.TRUE.equals(entity.getMustChangePwd());
    }

    @Override
    public void assertPasswordChangeNotRequired(Long userId) {
        if (isMustChangePassword(userId)) {
            audit(AuditCategory.SECURITY, "LOGIN_BLOCKED", userId, AuditResult.DENIED,
                Map.of("reason", "must_change_password"));
            throw new DbServiceException(DbErrorCode.IAM_PASSWORD_CHANGE_REQUIRED);
        }
    }

    private void markMustChange(Long userId) {
        Date now = new Date();
        DbUserSecurity entity = securityMapper.selectById(userId);
        if (entity == null) {
            entity = new DbUserSecurity();
            entity.setUserId(userId);
            entity.setCreateTime(now);
        }
        entity.setMustChangePwd(true);
        entity.setPolicyVersion(PasswordPolicyValidator.POLICY_VERSION);
        entity.setUpdateTime(now);
        upsert(entity);
    }

    private void clearMustChange(Long userId) {
        Date now = new Date();
        DbUserSecurity entity = securityMapper.selectById(userId);
        if (entity == null) {
            entity = new DbUserSecurity();
            entity.setUserId(userId);
            entity.setCreateTime(now);
        }
        entity.setMustChangePwd(false);
        entity.setPwdChangedAt(now);
        entity.setPolicyVersion(PasswordPolicyValidator.POLICY_VERSION);
        entity.setUpdateTime(now);
        upsert(entity);
    }

    private void upsert(DbUserSecurity entity) {
        if (securityMapper.selectById(entity.getUserId()) == null) {
            securityMapper.insert(entity);
        } else {
            securityMapper.updateById(entity);
        }
    }

    private void kickOffline(Long userId) {
        try {
            StpUtil.logout(userId);
        } catch (Exception ignored) {
            // 用户可能无在线会话，忽略
        }
    }

    private void audit(AuditCategory category, String action, Long targetUserId,
                       AuditResult result, Map<String, Object> details) {
        Long actorId;
        try {
            actorId = LoginHelper.getUserId();
        } catch (Exception e) {
            actorId = null;
        }
        auditService.append(new AuditEventInput(
            category, action, actorId, null,
            "USER", String.valueOf(targetUserId), null,
            result, null, null, null, details));
    }

    private void auditAnonymous(String action, String username, AuditResult result, Map<String, Object> details) {
        auditService.append(new AuditEventInput(
            AuditCategory.SECURITY, action, null, Map.of("username", username),
            "USER", username, null,
            result, null, null, null, details));
    }
}
