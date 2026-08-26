package org.dromara.db.resource.registry;

import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.spi.DataSourceConnector;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 连接器注册表（docs/02 第 7 节）。按数据源类型装配 Connector SPI 实现。
 *
 * @author DataGate
 */
@Component
public class ConnectorRegistry {

    private final Map<DataSourceType, DataSourceConnector> connectors = new EnumMap<>(DataSourceType.class);

    public ConnectorRegistry(List<DataSourceConnector> connectorList) {
        for (DataSourceConnector connector : connectorList) {
            connectors.put(connector.type(), connector);
        }
    }

    /**
     * 按类型获取连接器。未注册的类型返回空（调用方按"不支持"失败关闭处理）。
     */
    public Optional<DataSourceConnector> get(DataSourceType type) {
        return Optional.ofNullable(connectors.get(type));
    }

    /**
     * 是否已注册某类型连接器
     */
    public boolean supports(DataSourceType type) {
        return connectors.containsKey(type);
    }
}
