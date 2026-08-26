package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.security.SecretValue;

/**
 * 元数据提供者（docs/02 第 6.2 节 M1-04）。
 * 实现方使用专用监控/查询凭据，只读取元数据，不读取业务数据。
 *
 * @author DataGate
 */
public interface MetadataProvider {

    /**
     * 探测服务端版本
     */
    String serverVersion(ConnectionProfile profile, SecretValue secret);

    // M1 补充：库/Schema/表/列/索引/Redis 逻辑 DB 的增量拉取接口（以元数据版本对齐）
}
