package org.dromara.db.connector.mysql;

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
 * MySQL 元数据提供者（RES-005）。
 *
 * <p>只读 information_schema 系统目录：库 → 表/视图 → 列。
 * 使用平台专用凭据，不读取任何业务数据。</p>
 *
 * @author DataGate
 */
public class MysqlMetadataProvider implements MetadataProvider {

    @Override
    public String serverVersion(ConnectionProfile profile, SecretValue secret) {
        Properties props = buildProps(profile, secret);
        try (Connection conn = DriverManager.getConnection(buildJdbcUrl(profile), props);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
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
            Map<String, String> tablePaths = new HashMap<>();

            // 1. 数据库
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String db = rs.getString(1);
                    ResourceNode node = new ResourceNode(ResourceType.DATABASE, "", db,
                        Map.of("charset", String.valueOf(rs.getString(2))));
                    nodes.add(node);
                    dbPaths.put(db, node.canonicalPath());
                }
            }

            // 2. 表与视图
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_ROWS, TABLE_COMMENT " +
                    "FROM information_schema.TABLES ORDER BY TABLE_SCHEMA, TABLE_NAME");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String db = rs.getString(1);
                    String table = rs.getString(2);
                    String tableType = rs.getString(3);
                    boolean isView = "VIEW".equalsIgnoreCase(tableType);
                    Map<String, String> meta = new HashMap<>();
                    meta.put("estimatedRows", String.valueOf(rs.getLong(4)));
                    String comment = rs.getString(5);
                    if (comment != null && !comment.isBlank()) {
                        meta.put("comment", comment);
                    }
                    ResourceNode node = new ResourceNode(
                        isView ? ResourceType.VIEW : ResourceType.TABLE,
                        dbPaths.getOrDefault(db, ""), table, meta);
                    nodes.add(node);
                    tablePaths.put(db + "." + table, node.canonicalPath());
                }
            }

            // 3. 列
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, ORDINAL_POSITION " +
                    "FROM information_schema.COLUMNS ORDER BY TABLE_SCHEMA, TABLE_NAME, ORDINAL_POSITION");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1) + "." + rs.getString(2);
                    Map<String, String> meta = new HashMap<>();
                    meta.put("dataType", String.valueOf(rs.getString(4)));
                    meta.put("nullable", String.valueOf(rs.getString(5)));
                    String columnKey = rs.getString(6);
                    if (columnKey != null && !columnKey.isBlank()) {
                        meta.put("key", columnKey);
                    }
                    meta.put("ordinal", String.valueOf(rs.getInt(7)));
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
        StringBuilder url = new StringBuilder("jdbc:mysql://")
            .append(profile.host()).append(':').append(profile.port()).append('/')
            .append(profile.defaultDatabase() == null ? "" : profile.defaultDatabase())
            .append("?connectTimeout=").append(profile.connectTimeout().toMillis())
            .append("&socketTimeout=").append(profile.socketTimeout().toMillis());
        if (profile.tlsMode() == TlsMode.DISABLE) {
            url.append("&useSSL=false");
        }
        return url.toString();
    }
}
