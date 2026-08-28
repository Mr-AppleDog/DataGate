package org.dromara.db.connector.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.enums.ConnectorCapability;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.MetadataProvider;
import org.dromara.db.core.spi.QueryExecutor;
import org.dromara.db.core.spi.QueryParser;
import org.dromara.db.core.spi.SlowQueryProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis/Tair 连接器（docs/06 §8）。
 *
 * <p>只放行安全读命令（SCAN/GET/HGET 等 P0 白名单），写命令经变更工单；
 * SCAN 强制前缀与元素/字节上限，禁止脚本/阻塞/管理命令。RESP 结构化参数派发，
 * 不接受原始文本拼接。集群 MOVED/ASK 由 Lettuce 客户端拓扑处理。</p>
 *
 * @author DataGate
 */
@Component
public class RedisConnector implements DataSourceConnector {

    private final RedisMetadataProvider metadataProvider = new RedisMetadataProvider();
    private final RedisQueryParser queryParser = new RedisQueryParser();
    private final RedisQueryExecutor queryExecutor = new RedisQueryExecutor(queryParser);
    private final RedisSlowQueryProvider slowQueryProvider = new RedisSlowQueryProvider();
    private final RedisChangeExecutor changeExecutor = new RedisChangeExecutor();

    @Override
    public DataSourceType type() {
        return DataSourceType.REDIS;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(
            ConnectorCapability.METADATA_CATALOG,
            ConnectorCapability.READ_QUERY,
            ConnectorCapability.SLOW_QUERY_PULL,
            ConnectorCapability.QUERY_CANCEL
        );
    }

    @Override
    public ConnectionTestResult test(ConnectionProfile profile, SecretValue secret) {
        long start = System.nanoTime();
        RedisURI.Builder ub = RedisURI.builder()
            .withHost(profile.host())
            .withPort(profile.port())
            .withTimeout(Duration.ofSeconds(Math.max(profile.socketTimeout().toSeconds(), 1)));
        String db = profile.defaultDatabase();
        if (db != null && !db.isBlank()) {
            try {
                ub.withDatabase(Integer.parseInt(db.trim()));
            } catch (NumberFormatException ignored) {
                // 非数字 DB 忽略
            }
        }
        final RedisURI uri = ub.build();
        secret.useSecret(chars -> uri.setPassword(new String(chars)));
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            String pong = conn.sync().ping();
            String version = extractVersion(conn.sync().info());
            return ConnectionTestResult.ok(version + " / " + pong, capabilities(),
                Duration.ofNanos(System.nanoTime() - start));
        } catch (Exception e) {
            // 不向上抛驱动异常（含主机信息），只返回错误摘要（RES-004、docs/08 6.2）
            return ConnectionTestResult.fail(DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(),
                "连接失败: " + e.getClass().getSimpleName());
        }
    }

    /** 从 INFO 提取 redis_version（不暴露账号/主机细节）。 */
    private static String extractVersion(String info) {
        if (info == null) {
            return "unknown";
        }
        for (String line : info.split("\\r?\\n")) {
            if (line.startsWith("redis_version:")) {
                return line.substring("redis_version:".length()).trim();
            }
        }
        return "unknown";
    }

    @Override
    public MetadataProvider metadataProvider() {
        return metadataProvider;
    }

    @Override
    public QueryParser queryParser() {
        // M3：RESP 命令白名单/分类/前缀提取，失败关闭（docs/06 §8.2、§8.3）
        return queryParser;
    }

    @Override
    public QueryExecutor queryExecutor() {
        // M3：结构化派发/SAN 强制前缀+COUNT/元素字节上限/纵深再解析（docs/06 §8.1、§8.2、§11）
        return queryExecutor;
    }

    @Override
    public java.util.Optional<org.dromara.db.core.spi.ChangeExecutor> changeExecutor() {
        return java.util.Optional.of(changeExecutor);
    }

    @Override
    public Optional<SlowQueryProvider> slowQueryProvider() {
        // M4：SLOWLOG 采集（docs/07 §4.4）
        return Optional.of(slowQueryProvider);
    }
}
