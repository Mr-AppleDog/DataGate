package org.dromara.db.connector.mysql;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.MetadataProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * MySQL 元数据提供者（RES-005，M1 最小实现：版本探测；库/表/列同步在切片 C 完成）。
 *
 * @author DataGate
 */
public class MysqlMetadataProvider implements MetadataProvider {

    @Override
    public String serverVersion(ConnectionProfile profile, SecretValue secret) {
        Properties props = new Properties();
        props.setProperty("user", profile.username() == null ? "" : profile.username());
        secret.useSecret(chars -> props.setProperty("password", new String(chars)));
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

    private String buildJdbcUrl(ConnectionProfile profile) {
        return "jdbc:mysql://" + profile.host() + ":" + profile.port() + "/"
            + (profile.defaultDatabase() == null ? "" : profile.defaultDatabase())
            + "?connectTimeout=" + profile.connectTimeout().toMillis()
            + "&socketTimeout=" + profile.socketTimeout().toMillis();
    }
}
