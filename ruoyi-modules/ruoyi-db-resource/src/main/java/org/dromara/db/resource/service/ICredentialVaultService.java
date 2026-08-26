package org.dromara.db.resource.service;

import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.vo.DbCredentialVo;

import java.util.List;
import java.util.Optional;

/**
 * 凭据保险箱服务（CRED-001~007）。
 *
 * <p>铁律：</p>
 * <ul>
 *   <li>不提供任何读取明文/密文的对外接口（CRED-004）；</li>
 *   <li>解密仅供执行器/采集器等内部服务身份调用（CRED-005）；</li>
 *   <li>轮换产生新版本，旧版本退役，不更新密文（CRED-006）。</li>
 * </ul>
 *
 * @author DataGate
 */
public interface ICredentialVaultService {

    /**
     * 创建凭据及首个密文版本（明文只经过一次信封加密后入库）
     *
     * @param dataSourceId 数据源
     * @param purpose      用途
     * @param username     用户名
     * @param plaintext    明文秘密（调用后由实现方销毁入参副本）
     * @return 凭据 ID
     */
    Long createCredential(Long dataSourceId, CredentialPurpose purpose, String username, SecretValue plaintext);

    /**
     * 查询数据源的某用途凭据（仅元信息）
     */
    Optional<DbCredential> findActive(Long dataSourceId, CredentialPurpose purpose);

    /**
     * 解析并解密当前 ACTIVE 版本的秘密（仅限平台内部执行链路调用）。
     * 返回的 SecretValue 由调用方使用后销毁。
     *
     * @param credentialId 凭据 ID
     */
    SecretValue resolveActiveSecret(Long credentialId);

    /**
     * 禁用凭据（ CRED-007 审计由实现方写入）
     */
    boolean disable(Long credentialId);

    /**
     * 查询某数据源的凭据元信息列表（仅非秘密字段，CRED-004）
     */
    List<DbCredentialVo> listByDataSource(Long dataSourceId);
}
