package org.dromara.db.connector.mysql;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.enums.ConnectorCapability;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.MetadataProvider;
import org.dromara.db.core.spi.QueryExecutor;
import org.dromara.db.core.spi.QueryParser;
import org.dromara.db.core.spi.SlowQueryProvider;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * MySQL 连接器（docs/06）。覆盖自建 MySQL、RDS MySQL、PolarDB MySQL。
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>JDBC URL 由服务端按结构化字段构造，用户不能提交任意 URL/参数；</li>
 *   <li>密码经 SecretValue 短时使用，不进入 URL/日志/异常 message；</li>
 *   <li>连接池由执行器按数据源×用途建立（M2），本类只做连通性验证。</li>
 * </ul>
 *
 * @author DataGate
 */
@Component
public class MysqlConnector implements DataSourceConnector {

    private final MysqlMetadataProvider metadataProvider = new MysqlMetadataProvider();

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(
            ConnectorCapability.METADATA_CATALOG,
            ConnectorCapability.READ_QUERY,
            ConnectorCapability.EXPLAIN,
            ConnectorCapability.EXPORT,
            ConnectorCapability.CHANGE_DML,
            ConnectorCapability.CHANGE_DDL,
            ConnectorCapability.SLOW_QUERY_PULL,
            ConnectorCapability.QUERY_CANCEL
        );
    }

    @Override
    public ConnectionTestResult test(ConnectionProfile profile, SecretValue secret) {
        long start = System.nanoTime();
        Properties props = new Properties();
        props.setProperty("user", profile.username() == null ? "" : profile.username());
        secret.useSecret(chars -> props.setProperty("password", new String(chars)));
        try (Connection conn = DriverManager.getConnection(buildJdbcUrl(profile), props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
            String version = rs.next() ? rs.getString(1) : "unknown";
            return ConnectionTestResult.ok(version, capabilities(), Duration.ofNanos(System.nanoTime() - start));
        } catch (Exception e) {
            // 不向上抛驱动异常（含主机/用户名信息），只返回错误摘要（RES-004、docs/08 6.2）
            return ConnectionTestResult.fail(DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(),
                "连接失败: " + e.getClass().getSimpleName());
        } finally {
            // props 持有明文密码，使用后立即清除
            props.remove("password");
        }
    }

    /**
     * 由结构化字段构造 JDBC URL（禁止用户提交任意 URL）
     */
    public String buildJdbcUrl(ConnectionProfile profile) {
        StringBuilder url = new StringBuilder("jdbc:mysql://")
            .append(profile.host()).append(':').append(profile.port()).append('/');
        if (profile.defaultDatabase() != null && !profile.defaultDatabase().isBlank()) {
            url.append(profile.defaultDatabase());
        }
        url.append("?connectTimeout=").append(profile.connectTimeout().toMillis())
            .append("&socketTimeout=").append(profile.socketTimeout().toMillis());
        applyTls(url, profile.tlsMode());
        return url.toString();
    }

    private void applyTls(StringBuilder url, TlsMode tlsMode) {
        switch (tlsMode) {
            case DISABLE -> url.append("&useSSL=false");
            case PREFER -> url.append("&useSSL=true&requireSSL=false");
            case REQUIRE -> url.append("&useSSL=true&requireSSL=true");
            case VERIFY_CA, FULL -> url.append("&useSSL=true&requireSSL=true&verifyServerCertificate=true");
        }
    }

    @Override
    public MetadataProvider metadataProvider() {
        return metadataProvider;
    }

    @Override
    public QueryParser queryParser() {
        // M2 提供 Druid AST 解析实现；此前失败关闭
        throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, "MySQL 查询解析器将在 M2 提供");
    }

    @Override
    public QueryExecutor queryExecutor() {
        // M2 提供受控执行实现；此前失败关闭
        throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, "MySQL 执行器将在 M2 提供");
    }

    @Override
    public Optional<SlowQueryProvider> slowQueryProvider() {
        // M4 提供
        return Optional.empty();
    }
}
