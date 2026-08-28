package org.dromara.db.resource.registry;

import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.spi.DataSourceConnector;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.dromara.db.core.enums.FeatureGate;
import org.dromara.db.core.spi.FeatureGateService;

/**
 * 连接器注册表（docs/02 第 7 节）。按数据源类型装配 Connector SPI 实现。
 *
 * @author DataGate
 */
@Component
public class ConnectorRegistry {

    private final Map<DataSourceType, DataSourceConnector> connectors = new EnumMap<>(DataSourceType.class);
    private final Optional<FeatureGateService> gateService;

    public ConnectorRegistry(List<DataSourceConnector> connectorList, Optional<FeatureGateService> gateService) {
        this.gateService = gateService;
        for (DataSourceConnector connector : connectorList) {
            connectors.put(connector.type(), connector);
        }
    }

    /**
     * 按类型获取连接器（环境级灰度）。未注册或被功能开关关闭返回空（失败关闭）。
     */
    public Optional<DataSourceConnector> get(DataSourceType type) {
        return get(type, null);
    }

    /**
     * 按类型+数据源获取连接器（可按数据源灰度，docs/09 §14.3）。
     */
    public Optional<DataSourceConnector> get(DataSourceType type, Long dataSourceId) {
        DataSourceConnector c = connectors.get(type);
        if (c == null) {
            return Optional.empty();
        }
        if (gateService.isPresent()) {
            FeatureGate gate = gateOf(type);
            if (gate != null && !gateService.get().isEnabled(gate, dataSourceId)) {
                return Optional.empty();
            }
        }
        return Optional.of(c);
    }

    private static FeatureGate gateOf(DataSourceType t) {
        return switch (t) {
            case MYSQL -> FeatureGate.CONNECTOR_MYSQL;
            case POSTGRESQL -> FeatureGate.CONNECTOR_POSTGRESQL;
            case REDIS, TAIR -> FeatureGate.CONNECTOR_REDIS;
        };
    }

    /**
     * 是否已注册某类型连接器
     */
    public boolean supports(DataSourceType type) {
        return connectors.containsKey(type);
    }
}
