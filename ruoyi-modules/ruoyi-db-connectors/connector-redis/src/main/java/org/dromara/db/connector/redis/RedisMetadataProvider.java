package org.dromara.db.connector.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ResourceNode;
import org.dromara.db.core.enums.ResourceType;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.MetadataProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 元数据提供者（RES-005 / docs/06 §8.1、§9）。
 *
 * <p>只枚举逻辑 DB 与 keyspace 概要，不读取业务值。集群模式逻辑 DB 固定为 0
 * （docs/06 §8.1）。使用平台专用监控/查询凭据，密码经 SecretValue 短时使用。</p>
 *
 * @author DataGate
 */
public class RedisMetadataProvider implements MetadataProvider {

    @Override
    public String serverVersion(ConnectionProfile profile, SecretValue secret) {
        String info = rawInfo(profile, secret);
        if (info == null) {
            return "unknown";
        }
        return parseInfoSection(info).getOrDefault("redis_version", "unknown");
    }

    @Override
    public List<ResourceNode> fetchCatalog(ConnectionProfile profile, SecretValue secret) {
        String info = rawInfo(profile, secret);
        List<ResourceNode> nodes = new ArrayList<>();
        Map<String, String> all = parseInfoSection(info);
        // 集群模式逻辑 DB 固定 0（docs/06 §8.1）；非集群枚举 0..15
        boolean cluster = "1".equals(all.get("cluster_enabled"));
        int dbCount = cluster ? 1 : 16;
        // keyspace 概要：db0:keys=42,expires=0,avg_ttl=0
        Map<String, String> keyspace = parseKeyspace(info);
        for (int i = 0; i < dbCount; i++) {
            String name = String.valueOf(i);
            Map<String, String> meta = new HashMap<>();
            String ks = keyspace.get("db" + i);
            if (ks != null) {
                meta.put("keyspace", ks);
            }
            ResourceNode node = new ResourceNode(ResourceType.REDIS_DB, "", name, meta);
            nodes.add(node);
        }
        return nodes;
    }

    private String rawInfo(ConnectionProfile profile, SecretValue secret) {
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
            RedisCommands<String, String> sync = conn.sync();
            return sync.info();
        } catch (Exception e) {
            // 不携带连接串/密码细节（同步服务统一遮蔽）
            throw new IllegalStateException("Redis 元数据拉取失败: " + e.getClass().getSimpleName());
        }
    }

    /** 解析 INFO 的非节区键值对。 */
    private static Map<String, String> parseInfoSection(String info) {
        Map<String, String> map = new HashMap<>();
        if (info == null) {
            return map;
        }
        for (String line : info.split("\\r?\\n")) {
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":")) {
                continue;
            }
            int idx = line.indexOf(':');
            map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return map;
    }

    /** 解析 Keyspace 节（db0:keys=42,expires=0,avg_ttl=0）。 */
    private static Map<String, String> parseKeyspace(String info) {
        Map<String, String> map = new HashMap<>();
        if (info == null) {
            return map;
        }
        boolean inKeyspace = false;
        for (String line : info.split("\\r?\\n")) {
            if (line.startsWith("# Keyspace")) {
                inKeyspace = true;
                continue;
            }
            if (line.startsWith("#")) {
                inKeyspace = false;
                continue;
            }
            if (inKeyspace && line.contains(":")) {
                int idx = line.indexOf(':');
                map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return map;
    }
}
