package org.dromara.db.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.CredentialVersionStatus;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.resource.credential.CredentialCryptoService;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbCredentialVersion;
import org.dromara.db.resource.domain.vo.DbCredentialVo;
import org.dromara.db.resource.mapper.DbCredentialMapper;
import org.dromara.db.resource.mapper.DbCredentialVersionMapper;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 凭据保险箱实现。明文只在内存中经 {@link SecretValue} 短时存在；
 * 密文、Nonce、DEK 不出本类边界（不返回、不记录）。
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class CredentialVaultServiceImpl implements ICredentialVaultService {

    private final CredentialCryptoService cryptoService;
    private final DbCredentialMapper credentialMapper;
    private final DbCredentialVersionMapper credentialVersionMapper;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createCredential(Long dataSourceId, CredentialPurpose purpose, String username, SecretValue plaintext) {
        // 唯一约束兜底：同数据源同用途只能有一个未删除凭据
        boolean exists = credentialMapper.exists(new LambdaQueryWrapper<DbCredential>()
            .eq(DbCredential::getDataSourceId, dataSourceId)
            .eq(DbCredential::getPurpose, purpose.name()));
        if (exists) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_ROTATION_CONFLICT,
                "该数据源已存在 " + purpose + " 凭据，请使用轮换接口");
        }

        DbCredential credential = new DbCredential();
        credential.setDataSourceId(dataSourceId);
        credential.setPurpose(purpose.name());
        credential.setUsername(username);
        credential.setStatus("ACTIVE");
        credentialMapper.insert(credential);

        // 首个版本：信封加密后入库（CRED-002）
        DbCredentialVersion version = new DbCredentialVersion();
        version.setCredentialId(credential.getId());
        version.setVersionNo(1);
        CredentialCryptoService.Envelope envelope = cryptoService.encrypt(
            credential.getId(), dataSourceId, purpose, 1, plaintext);
        version.setCiphertext(envelope.ciphertext());
        version.setNonce(envelope.nonce());
        version.setWrappedDek(envelope.wrappedDek());
        version.setDekNonce(envelope.dekNonce());
        version.setAlgorithm(envelope.algorithm());
        version.setKeyVersion(envelope.keyVersion());
        version.setSecretFingerprint(envelope.secretFingerprint());
        version.setStatus(CredentialVersionStatus.PENDING.name());
        version.setCreatedAt(new Date());
        credentialVersionMapper.insert(version);

        credential.setActiveVersionId(version.getId());
        credentialMapper.updateById(credential);

        // CRED-007：凭据创建审计（只记元信息，绝不记秘密）
        auditService.append(new AuditEventInput(
            AuditCategory.CREDENTIAL, "CREDENTIAL_CREATE", safeUserId(),
            Map.of("username", safeUsername()),
            "CREDENTIAL", String.valueOf(credential.getId()),
            Map.of("dataSourceId", dataSourceId, "purpose", purpose.name()),
            AuditResult.SUCCESS, null, null, null, null));
        return credential.getId();
    }

    @Override
    public Optional<DbCredential> findActive(Long dataSourceId, CredentialPurpose purpose) {
        return Optional.ofNullable(credentialMapper.selectOne(new LambdaQueryWrapper<DbCredential>()
            .eq(DbCredential::getDataSourceId, dataSourceId)
            .eq(DbCredential::getPurpose, purpose.name())
            .eq(DbCredential::getStatus, "ACTIVE")));
    }

    @Override
    public SecretValue resolveActiveSecret(Long credentialId) {
        DbCredential credential = credentialMapper.selectById(credentialId);
        if (credential == null || !"ACTIVE".equals(credential.getStatus())) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_DISABLED);
        }
        Long versionId = credential.getActiveVersionId();
        if (versionId == null) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_INVALID, "凭据无有效版本");
        }
        DbCredentialVersion version = credentialVersionMapper.selectById(versionId);
        if (version == null || !CredentialVersionStatus.ACTIVE.name().equals(version.getStatus())
            && !CredentialVersionStatus.PENDING.name().equals(version.getStatus())) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_INVALID, "凭据版本状态非法");
        }
        CredentialCryptoService.Envelope envelope = new CredentialCryptoService.Envelope(
            version.getCiphertext(), version.getNonce(), version.getWrappedDek(), version.getDekNonce(),
            version.getAlgorithm(), version.getKeyVersion(), version.getSecretFingerprint());
        CredentialPurpose purpose = CredentialPurpose.valueOf(credential.getPurpose());
        // 解密到 SecretValue（调用方使用后销毁）
        final char[][] holder = new char[1][];
        cryptoService.decrypt(envelope, credentialId, credential.getDataSourceId(), purpose,
            version.getVersionNo(), chars -> holder[0] = chars.clone());
        if (holder[0] == null) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_INVALID, "凭据解密失败");
        }
        return SecretValue.of(holder[0]);
    }

    @Override
    public boolean disable(Long credentialId) {
        DbCredential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            return false;
        }
        credential.setStatus("DISABLED");
        boolean ok = credentialMapper.updateById(credential) > 0;
        if (ok) {
            auditService.append(new AuditEventInput(
                AuditCategory.CREDENTIAL, "CREDENTIAL_DISABLE", safeUserId(),
                Map.of("username", safeUsername()),
                "CREDENTIAL", String.valueOf(credentialId),
                Map.of("dataSourceId", credential.getDataSourceId(), "purpose", credential.getPurpose()),
                AuditResult.SUCCESS, null, null, null, null));
        }
        return ok;
    }

    @Override
    public List<DbCredentialVo> listByDataSource(Long dataSourceId) {
        return credentialMapper.selectVoList(new LambdaQueryWrapper<DbCredential>()
            .eq(DbCredential::getDataSourceId, dataSourceId)
            .orderByAsc(DbCredential::getPurpose));
    }

    private Long safeUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
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
