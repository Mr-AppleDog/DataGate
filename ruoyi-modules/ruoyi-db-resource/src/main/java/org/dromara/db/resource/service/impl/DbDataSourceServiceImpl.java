package org.dromara.db.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.domain.bo.DbDataSourceBo;
import org.dromara.db.resource.mapper.DbDataSourceMapper;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.dromara.db.resource.support.NetworkAddressValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源管理实现。所有写操作经状态机与 SSRF 校验；连接测试只返回分项能力结果。
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class DbDataSourceServiceImpl implements IDbDataSourceService {

    private final DbDataSourceMapper dataSourceMapper;
    private final NetworkAddressValidator networkAddressValidator;
    private final ConnectorRegistry connectorRegistry;
    private final ICredentialVaultService credentialVaultService;
    private final IAuditService auditService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(DbDataSourceBo bo) {
        // SSRF/网络白名单校验（docs/08 第 7 节）
        NetworkAddressValidator.ValidationResult check =
            networkAddressValidator.validate(bo.getHost(), bo.getPort());
        if (!check.allowed()) {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.SECURITY, "DATASOURCE_SSRF_BLOCKED", LoginHelper.getUserId(),
                Map.of("username", LoginHelper.getUsername() == null ? "" : LoginHelper.getUsername()),
                "DATA_SOURCE", null, Map.of("host", maskHost(bo.getHost()), "port", bo.getPort()),
                AuditResult.DENIED, null, null, null, Map.of("reason", check.reason())));
            throw new DbServiceException(DbErrorCode.RESOURCE_SSRF_BLOCKED);
        }

        // 环境必须存在且启用
        // 同环境下名称唯一由唯一索引兜底；这里提前友好报错
        boolean exists = dataSourceMapper.exists(new LambdaQueryWrapper<DbDataSource>()
            .eq(DbDataSource::getEnvironmentId, bo.getEnvironmentId())
            .eq(DbDataSource::getName, bo.getName()));
        if (exists) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "同环境下数据源名称已存在");
        }

        DbDataSource entity = MapstructUtils.convert(bo, DbDataSource.class);
        entity.setStatus(DataSourceStatus.DRAFT.name());
        entity.setPolicyVersion(0L);
        dataSourceMapper.insert(entity);

        auditService.append(new AuditEventInput(
            AuditCategory.CONFIG, "DATASOURCE_CREATE", LoginHelper.getUserId(),
            Map.of("username", safeUsername()),
            "DATA_SOURCE", String.valueOf(entity.getId()),
            Map.of("name", entity.getName(), "type", entity.getType(), "env", bo.getEnvironmentId()),
            AuditResult.SUCCESS, null, null, null, null));
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateByBo(DbDataSourceBo bo) {
        if (bo.getId() == null) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "更新必须携带数据源 ID");
        }
        if (bo.getVersion() == null) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "更新必须携带版本号");
        }
        DbDataSource entity = dataSourceMapper.selectById(bo.getId());
        if (entity == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE);
        }
        // 主机/端口变化属于高风险操作：重新 SSRF 校验
        NetworkAddressValidator.ValidationResult check =
            networkAddressValidator.validate(bo.getHost(), bo.getPort());
        if (!check.allowed()) {
            throw new DbServiceException(DbErrorCode.RESOURCE_SSRF_BLOCKED);
        }
        DbDataSource update = MapstructUtils.convert(bo, DbDataSource.class);
        update.setStatus(entity.getStatus());
        boolean ok = dataSourceMapper.updateById(update) > 0;
        if (ok) {
            auditService.append(new AuditEventInput(
                AuditCategory.CONFIG, "DATASOURCE_UPDATE", LoginHelper.getUserId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(update.getId()), Map.of("name", update.getName()),
                AuditResult.SUCCESS, null, null, null, null));
        }
        return ok;
    }

    @Override
    public ConnectionTestResult verify(Long id) {
        DbDataSource ds = dataSourceMapper.selectById(id);
        if (ds == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE);
        }
        DataSourceType type = DataSourceType.valueOf(ds.getType());
        Optional<DataSourceConnector> connector = connectorRegistry.get(type);
        if (connector.isEmpty()) {
            throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, "该类型连接器未注册");
        }

        // 取监控凭据，其次查询凭据（CRED-001）
        Optional<DbCredential> credential = credentialVaultService.findActive(id, CredentialPurpose.MONITOR)
            .or(() -> credentialVaultService.findActive(id, CredentialPurpose.QUERY));
        if (credential.isEmpty()) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_INVALID, "请先配置 MONITOR 或 QUERY 凭据");
        }

        // 连接前复核 DNS 解析（防 DNS rebinding，docs/08 第 7 节）
        if (!networkAddressValidator.recheckResolved(ds.getHost())) {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.SECURITY, "DATASOURCE_DNS_RECHECK_FAILED", LoginHelper.getUserId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(id), Map.of(),
                AuditResult.DENIED, null, null, null, null));
            throw new DbServiceException(DbErrorCode.RESOURCE_SSRF_BLOCKED, "主机解析结果不复核通过");
        }

        ConnectionProfile profile = new ConnectionProfile(
            ds.getHost(), ds.getPort(), ds.getDefaultDatabase(), credential.get().getUsername(),
            null, TlsMode.valueOf(ds.getTlsMode()), null, null);

        ConnectionTestResult result;
        try (SecretValue secret = credentialVaultService.resolveActiveSecret(credential.get().getId())) {
            result = connector.get().test(profile, secret);
        }

        // 更新验证时间与状态；错误只记标准错误码
        DbDataSource update = new DbDataSource();
        update.setId(id);
        update.setLastVerifiedAt(new Date());
        if (result.success()) {
            update.setStatus(DataSourceStatus.VERIFYING.name());
            update.setLastErrorCode(null);
        } else {
            update.setStatus(DataSourceStatus.ERROR.name());
            update.setLastErrorCode(result.errorCode());
        }
        dataSourceMapper.updateById(update);

        auditService.append(new AuditEventInput(
            AuditCategory.CREDENTIAL, "DATASOURCE_VERIFY", LoginHelper.getUserId(),
            Map.of("username", safeUsername()),
            "DATA_SOURCE", String.valueOf(id), Map.of("success", result.success()),
            result.success() ? AuditResult.SUCCESS : AuditResult.FAILURE,
            null, null, null,
            result.success() ? Map.of("serverVersion", String.valueOf(result.serverVersion()))
                : Map.of("errorCode", String.valueOf(result.errorCode()))));
        return result;
    }

    @Override
    public boolean enable(Long id) {
        DbDataSource ds = dataSourceMapper.selectById(id);
        if (ds == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE);
        }
        if (!DataSourceStatus.DISABLED.name().equals(ds.getStatus())
            && !DataSourceStatus.VERIFYING.name().equals(ds.getStatus())) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT,
                "只有验证成功或已禁用的数据源可以启用");
        }
        return updateStatus(id, DataSourceStatus.ACTIVE, "DATASOURCE_ENABLE");
    }

    @Override
    public boolean disable(Long id) {
        DbDataSource ds = dataSourceMapper.selectById(id);
        if (ds == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE);
        }
        if (!DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "只有运行中的数据源可以禁用");
        }
        return updateStatus(id, DataSourceStatus.DISABLED, "DATASOURCE_DISABLE");
    }

    private boolean updateStatus(Long id, DataSourceStatus status, String auditAction) {
        DbDataSource update = new DbDataSource();
        update.setId(id);
        update.setStatus(status.name());
        boolean ok = dataSourceMapper.updateById(update) > 0;
        if (ok) {
            auditService.append(new AuditEventInput(
                AuditCategory.CONFIG, auditAction, LoginHelper.getUserId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(id), Map.of("status", status.name()),
                AuditResult.SUCCESS, null, null, null, null));
        }
        return ok;
    }

    @Override
    public DbDataSource queryById(Long id) {
        return dataSourceMapper.selectById(id);
    }

    private String safeUsername() {
        try {
            String name = LoginHelper.getUsername();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private String maskHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) + ".***" : "***";
    }
}
