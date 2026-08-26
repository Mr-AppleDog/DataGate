package org.dromara.db.core.domain;

import org.dromara.db.core.enums.TlsMode;

import java.time.Duration;
import java.util.Map;

/**
 * 连接配置（docs/02 第 7.3 节）。只含非秘密信息：
 * 主机、端口、数据库、用户名、参数白名单、TLS 模式和超时。
 * 密码、Token、AccessKey Secret 永远使用 SecretValue 单独传递。
 *
 * @param host             主机
 * @param port             端口
 * @param defaultDatabase  默认库（可空）
 * @param username         数据库用户名（非秘密，docs/04：可在受限管理页显示）
 * @param options          白名单连接参数（禁止包含密码类键）
 * @param tlsMode          TLS 模式
 * @param connectTimeout   连接超时
 * @param socketTimeout    读写超时
 * @author DataGate
 */
public record ConnectionProfile(
    String host,
    int port,
    String defaultDatabase,
    String username,
    Map<String, String> options,
    TlsMode tlsMode,
    Duration connectTimeout,
    Duration socketTimeout
) {

    public ConnectionProfile {
        options = options == null ? Map.of() : Map.copyOf(options);
        tlsMode = tlsMode == null ? TlsMode.PREFER : tlsMode;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        socketTimeout = socketTimeout == null ? Duration.ofSeconds(30) : socketTimeout;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }
}
