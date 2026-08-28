package org.dromara.db.connector.postgresql;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ResourceNode;
import org.dromara.db.core.enums.ResourceType;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.MetadataProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL 元数据提供者（RES-005 / docs/06 §9、§7.2）。
 *
 * <p>只读 pg_catalog 系统目录：数据库 → Schema → 表/视图/物化视图 → 列。
 * 使用平台专用监控/查询凭据，不读取任何业务数据。pg_catalog/information_schema
 * 安全视图访问由鉴权层过滤，本提供者只负责拉取结构化目录快照。</p>
 *
 * @author DataGate
 */
public class PostgresqlMetadataProvider implements MetadataProvider {

    @Override
    public String serverVersion(ConnectionProfile profile, SecretValue secret) {
        Properties props = buildProps(profile, secret);
        try (Connection conn = DriverManager.getConnection(buildJdbcUrl(profile), props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : "unknown";
        } catch (Exception e) {
            throw new IllegalStateException("获取服务端版本失败: " + e.getClass().getSimpleName());
        } finally {
            props.remove("password");
        }
    }

    @Override
    public List<ResourceNode> fetchCatalog(ConnectionProfile profile, SecretValue secret) {
        Properties props = buildProps(profile, secret);
        try (Connection conn = DriverManager.getConnection(buildJdbcUrl(profile), props)) {
            List<ResourceNode> nodes = new ArrayList<>();
            Map<String, String> dbPaths = new HashMap<>();
            Map<String, String> schemaPaths = new HashMap<>();
            Map<String, String> tablePaths = new HashMap<>();

            // 1. 数据库（pg_database）
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT datname, pg_encoding_to_char(encoding) FROM pg_database "
                    + "WHERE datallowconn ORDER BY datname");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String db = rs.getString(1);
                    ResourceNode node = new ResourceNode(ResourceType.DATABASE, "", db,
                        Map.of("encoding", String.valueOf(rs.getString(2))));
                    nodes.add(node);
                    dbPaths.put(db, node.canonicalPath());
                }
            }

            // 2. Schema（pg_namespace，排除系统 schema）
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT nspname FROM pg_namespace "
                    + "WHERE nspname NOT IN ('pg_toast','pg_catalog','information_schema') "
                    + "ORDER BY nspname");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString(1);
                    ResourceNode node = new ResourceNode(ResourceType.SCHEMA, "", schema, Map.of());
                    nodes.add(node);
                    schemaPaths.put(schema, node.canonicalPath());
                }
            }

            // 3. 表 / 视图 / 物化视图（pg_class）
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.nspname, c.relname, c.relkind, c.reltuples "
                    + "FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid "
                    + "WHERE n.nspname NOT IN ('pg_toast','pg_catalog','information_schema') "
                    + "AND c.relkind IN ('r','v','m','p','f') ORDER BY n.nspname, c.relname");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String schema = rs.getString(1);
                    String table = rs.getString(2);
                    char relkind = rs.getString(3).charAt(0);
                    ResourceType rt = switch (relkind) {
                        case 'v' -> ResourceType.VIEW;
                        case 'm' -> ResourceType.MATERIALIZED_VIEW;
                        default -> ResourceType.TABLE;
                    };
                    Map<String, String> meta = new HashMap<>();
                    meta.put("estimatedRows", String.valueOf(rs.getLong(4)));
                    String parent = schemaPaths.getOrDefault(schema, "");
                    ResourceNode node = new ResourceNode(rt, parent, table, meta);
                    nodes.add(node);
                    tablePaths.put(schema + "." + table, node.canonicalPath());
                }
            }

            // 4. 列（pg_attribute）
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.nspname, c.relname, a.attname, t.typname, a.attnotnull, a.attnum "
                    + "FROM pg_attribute a "
                    + "JOIN pg_class c ON a.attrelid = c.oid "
                    + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                    + "JOIN pg_type t ON a.atttypid = t.oid "
                    + "WHERE n.nspname NOT IN ('pg_toast','pg_catalog','information_schema') "
                    + "AND a.attnum > 0 AND NOT a.attisdropped "
                    + "ORDER BY n.nspname, c.relname, a.attnum");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1) + "." + rs.getString(2);
                    Map<String, String> meta = new HashMap<>();
                    meta.put("dataType", String.valueOf(rs.getString(4)));
                    meta.put("nullable", String.valueOf(!rs.getBoolean(5)));
                    meta.put("ordinal", String.valueOf(rs.getInt(6)));
                    nodes.add(new ResourceNode(ResourceType.COLUMN,
                        tablePaths.getOrDefault(key, ""), rs.getString(3), meta));
                }
            }
            return nodes;
        } catch (Exception e) {
            // 不携带连接串/用户名/驱动 message 细节（同步服务统一遮蔽）
            throw new IllegalStateException("元数据拉取失败: " + e.getClass().getSimpleName());
        } finally {
            props.remove("password");
        }
    }

    private Properties buildProps(ConnectionProfile profile, SecretValue secret) {
        Properties props = new Properties();
        props.setProperty("user", profile.username() == null ? "" : profile.username());
        secret.useSecret(chars -> props.setProperty("password", new String(chars)));
        return props;
    }

    private String buildJdbcUrl(ConnectionProfile profile) {
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
            .append(profile.host()).append(':').append(profile.port());
        if (profile.defaultDatabase() != null && !profile.defaultDatabase().isBlank()) {
            url.append('/').append(profile.defaultDatabase());
        }
        url.append("?connectTimeout=").append(profile.connectTimeout().toSeconds())
            .append("&socketTimeout=").append(profile.socketTimeout().toSeconds());
        if (profile.tlsMode() == TlsMode.DISABLE) {
            url.append("&sslmode=disable");
        }
        return url.toString();
    }
}
