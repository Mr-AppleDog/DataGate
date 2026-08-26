package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ResourceNode;
import org.dromara.db.core.security.SecretValue;

import java.util.List;

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

    /**
     * 全量拉取目录快照（库/Schema/表/视图/列）。
     *
     * <p>实现方约束：只读 information_schema / 系统目录；
     * 连接超时与读取超时使用 profile 中的值；失败向上抛异常（由同步服务遮蔽）。</p>
     *
     * @return 扁平节点列表，父节点必须先于子节点出现
     */
    List<ResourceNode> fetchCatalog(ConnectionProfile profile, SecretValue secret);
}
