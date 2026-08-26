package org.dromara.system.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.system.domain.DbUserTotp;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.mapper.SysUserTotpMapper;
import org.dromara.system.service.ISysUserTotpService;
import org.dromara.system.totp.TotpSecretCipher;
import org.dromara.system.totp.TotpSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 用户 TOTP 实现。密钥明文只在内存短时存在，KEK 加密入库；
 * 验证通过更新时间步防重放；恢复码用后即从哈希集合移除。
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class SysUserTotpServiceImpl implements ISysUserTotpService {

    private final SysUserTotpMapper totpMapper;
    private final SysUserMapper userMapper;
    private final TotpSecretCipher cipher;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TotpSetupResult setup() {
        Long userId = LoginHelper.getUserId();
        SysUserVo user = userMapper.selectVoById(userId);
        String account = user == null ? String.valueOf(userId) : user.getUserName();

        byte[] secret = TotpSupport.generateSecret();
        try {
            TotpSecretCipher.Sealed sealed = cipher.seal(userId, secret);
            String[] recoveryCodes = TotpSupport.generateRecoveryCodes();
            List<String> hashes = Arrays.stream(recoveryCodes).map(TotpSupport::recoveryHash).toList();

            DbUserTotp entity = new DbUserTotp();
            entity.setUserId(userId);
            entity.setCiphertext(sealed.ciphertext());
            entity.setNonce(sealed.nonce());
            entity.setKeyVersion(sealed.keyVersion());
            entity.setStatus("PENDING");
            entity.setRecoveryHashes(JsonUtils.toJsonString(hashes));
            entity.setCreateTime(new Date());
            entity.setUpdateTime(new Date());
            // 覆盖历史记录（重新绑定）
            if (totpMapper.selectById(userId) == null) {
                totpMapper.insert(entity);
            } else {
                totpMapper.updateById(entity);
            }

            auditService.append(new AuditEventInput(
                AuditCategory.SECURITY, "TOTP_SETUP", userId,
                Map.of("username", account),
                "USER", String.valueOf(userId), Map.of("username", account),
                AuditResult.SUCCESS, null, null, null, null));

            String base32 = TotpSupport.base32Encode(secret);
            String url = "otpauth://totp/DataGate:" + account + "?secret=" + base32
                + "&issuer=DataGate&digits=6&period=30";
            return new TotpSetupResult(base32, url, List.of(recoveryCodes));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String code) {
        Long userId = LoginHelper.getUserId();
        DbUserTotp entity = totpMapper.selectById(userId);
        if (entity == null || !"PENDING".equals(entity.getStatus())) {
            throw new DbServiceException(DbErrorCode.IAM_MFA_REQUIRED, "没有待确认的 TOTP 绑定，请先发起设置");
        }
        byte[] secret = cipher.unseal(userId, entity.getCiphertext(), entity.getNonce(), entity.getKeyVersion());
        try {
            Long step = TotpSupport.verify(secret, code);
            if (step == null) {
                auditService.append(new AuditEventInput(
                    AuditCategory.SECURITY, "TOTP_CONFIRM", userId,
                    Map.of("username", safeUsername()),
                    "USER", String.valueOf(userId), null,
                    AuditResult.FAILURE, null, null, null, Map.of("reason", "bad_code")));
                throw new DbServiceException(DbErrorCode.IAM_MFA_REQUIRED, "验证码不正确");
            }
            entity.setStatus("ACTIVE");
            entity.setBoundAt(new Date());
            entity.setLastStep(step);
            entity.setUpdateTime(new Date());
            totpMapper.updateById(entity);

            auditService.append(new AuditEventInput(
                AuditCategory.SECURITY, "TOTP_BIND", userId,
                Map.of("username", safeUsername()),
                "USER", String.valueOf(userId), null,
                AuditResult.SUCCESS, null, null, null, null));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public TotpStatus statusOf(Long userId) {
        DbUserTotp entity = userId == null ? null : totpMapper.selectById(userId);
        if (entity == null) {
            return new TotpStatus(false, false);
        }
        return new TotpStatus("ACTIVE".equals(entity.getStatus()), "PENDING".equals(entity.getStatus()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long userId, String code, boolean byAdmin) {
        DbUserTotp entity = totpMapper.selectById(userId);
        if (entity == null) {
            return;
        }
        if (!byAdmin) {
            // 本人解绑必须持有效验证码
            byte[] secret = cipher.unseal(userId, entity.getCiphertext(), entity.getNonce(), entity.getKeyVersion());
            try {
                if (TotpSupport.verify(secret, code) == null) {
                    throw new DbServiceException(DbErrorCode.IAM_MFA_REQUIRED, "验证码不正确");
                }
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }
        totpMapper.deleteById(userId);
        auditService.append(new AuditEventInput(
            AuditCategory.SECURITY, byAdmin ? "TOTP_UNBIND_ADMIN" : "TOTP_UNBIND", LoginHelper.getUserId(),
            Map.of("username", safeUsername()),
            "USER", String.valueOf(userId), Map.of("byAdmin", byAdmin),
            AuditResult.SUCCESS, null, null, null, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assertLoginAllowed(Long userId, String code) {
        DbUserTotp entity = totpMapper.selectById(userId);
        if (entity == null || !"ACTIVE".equals(entity.getStatus())) {
            return;
        }
        if (StringUtils.isBlank(code)) {
            throw new DbServiceException(DbErrorCode.IAM_MFA_REQUIRED);
        }
        // 恢复码优先（用后销毁）
        List<String> hashes = parseHashes(entity.getRecoveryHashes());
        String recoveryHash = TotpSupport.recoveryHash(code);
        if (hashes.contains(recoveryHash)) {
            hashes.remove(recoveryHash);
            entity.setRecoveryHashes(JsonUtils.toJsonString(hashes));
            entity.setLastUsedAt(new Date());
            entity.setUpdateTime(new Date());
            totpMapper.updateById(entity);
            auditService.append(new AuditEventInput(
                AuditCategory.SECURITY, "TOTP_RECOVERY_USED", userId, null,
                "USER", String.valueOf(userId), null,
                AuditResult.SUCCESS, null, null, null, null));
            return;
        }
        byte[] secret = cipher.unseal(userId, entity.getCiphertext(), entity.getNonce(), entity.getKeyVersion());
        try {
            Long step = TotpSupport.verify(secret, code);
            // 防重放：同一步或更早的步不可再用
            if (step == null || (entity.getLastStep() != null && step <= entity.getLastStep())) {
                auditService.appendIsolated(new AuditEventInput(
                    AuditCategory.SECURITY, "TOTP_LOGIN_REJECTED", userId, null,
                    "USER", String.valueOf(userId), null,
                    AuditResult.DENIED, null, null, null,
                    Map.of("reason", step == null ? "bad_code" : "replay")));
                throw new DbServiceException(DbErrorCode.IAM_MFA_REQUIRED);
            }
            entity.setLastStep(step);
            entity.setLastUsedAt(new Date());
            entity.setUpdateTime(new Date());
            totpMapper.updateById(entity);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private List<String> parseHashes(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        List<String> list = JsonUtils.parseArray(json, String.class);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    private String safeUsername() {
        try {
            String name = LoginHelper.getUsername();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }
}
