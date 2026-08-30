package org.dromara.db.resource.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.resource.domain.bo.DbConnectionTestBo;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.support.NetworkAddressValidator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 使用临时凭据执行连接测试（RES-004）。临时密码仅进入 SecretValue，结束后立即清零，永不持久化。
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class DataSourceConnectionTestService {

    private static final Pattern FORBIDDEN_OPTION_KEY =
        Pattern.compile("(?i)(password|passwd|pwd|secret|token|credential|access[-_]?key|auth)");

    private final NetworkAddressValidator networkAddressValidator;
    private final ConnectorRegistry connectorRegistry;
    private final IAuditService auditService;

    public ConnectionTestResult test(DbConnectionTestBo bo) {
        try {
            NetworkAddressValidator.ValidationResult addressCheck =
                networkAddressValidator.validate(bo.getHost(), bo.getPort());
            if (!addressCheck.allowed()) {
                auditBlocked(bo, addressCheck.reason());
                throw new DbServiceException(DbErrorCode.RESOURCE_SSRF_BLOCKED, addressCheck.reason());
            }
            if (!networkAddressValidator.recheckResolved(bo.getHost())) {
                auditBlocked(bo, "主机解析结果不复核通过");
                throw new DbServiceException(DbErrorCode.RESOURCE_SSRF_BLOCKED, "主机解析结果不复核通过");
            }

            DataSourceType type = DataSourceType.valueOf(bo.getType());
            Optional<DataSourceConnector> connector = connectorRegistry.get(type);
            if (connector.isEmpty()) {
                throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, "该类型连接器未注册");
            }

            ConnectionProfile profile = new ConnectionProfile(
                bo.getHost(), bo.getPort(), bo.getDefaultDatabase(), bo.getUsername(),
                safeOptions(bo.getConnectionOptions()), parseTlsMode(bo.getTlsMode()), null, null);

            ConnectionTestResult result;
            try (SecretValue secret = SecretValue.of(bo.getPassword())) {
                result = connector.get().test(profile, secret);
            } catch (Exception e) {
                result = ConnectionTestResult.fail(
                    DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(), "连接失败，请检查地址、TLS 配置和测试凭据");
            }
            auditResult(bo, result);
            return result;
        } finally {
            bo.clearPassword();
        }
    }

    private Map<String, String> safeOptions(Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : options.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.length() > 64 || FORBIDDEN_OPTION_KEY.matcher(key).find()) {
                throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "连接参数包含被禁止的键");
            }
            if (value != null && value.length() > 512) {
                throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "连接参数值超长");
            }
            safe.put(key, value == null ? "" : value);
        }
        return Map.copyOf(safe);
    }

    private TlsMode parseTlsMode(String tlsMode) {
        return tlsMode == null || tlsMode.isBlank() ? TlsMode.PREFER : TlsMode.valueOf(tlsMode);
    }

    private void auditBlocked(DbConnectionTestBo bo, String reason) {
        auditService.appendIsolated(new AuditEventInput(
            AuditCategory.SECURITY, "DATASOURCE_CONNECTION_TEST_BLOCKED", currentUserId(),
            Map.of("username", currentUsername()), "DATA_SOURCE", null,
            Map.of("type", bo.getType(), "host", maskHost(bo.getHost()), "port", bo.getPort()),
            AuditResult.DENIED, null, null, null, Map.of("reason", reason)));
    }

    private void auditResult(DbConnectionTestBo bo, ConnectionTestResult result) {
        Map<String, Object> detail = result.success()
            ? Map.of("serverVersion", String.valueOf(result.serverVersion()))
            : Map.of("errorCode", String.valueOf(result.errorCode()));
        auditService.append(new AuditEventInput(
            AuditCategory.CREDENTIAL, "DATASOURCE_CONNECTION_TEST", currentUserId(),
            Map.of("username", currentUsername()), "DATA_SOURCE", null,
            Map.of("type", bo.getType(), "host", maskHost(bo.getHost()), "port", bo.getPort()),
            result.success() ? AuditResult.SUCCESS : AuditResult.FAILURE,
            null, null, null, detail));
    }

    private Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentUsername() {
        try {
            String username = LoginHelper.getUsername();
            return username == null ? "" : username;
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
