package org.dromara.db.core.domain;

import org.dromara.db.core.enums.ResourceType;

import java.util.Map;

/**
 * 元数据快照节点（docs/03 第 3 节资源模型、docs/04 第 3.6 节）。
 *
 * <p>连接器拉取的一侧视图；入库时由资源目录服务换算为 dbg_resource 行。
 * 只携带非秘密元数据（类型、行数估计、注释等），绝不携带业务数据。</p>
 *
 * @param type         资源类型
 * @param parentPath   父节点 canonical 路径（根数据库节点的父路径为空串）
 * @param physicalName 物理名（如表名）
 * @param metadata     非秘密元数据（dataType/nullable/comment/估算行数等）
 * @author DataGate
 */
public record ResourceNode(
    ResourceType type,
    String parentPath,
    String physicalName,
    Map<String, String> metadata
) {

    public ResourceNode {
        parentPath = parentPath == null ? "" : parentPath;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 规范路径：{@code parentPath + "/" + 类型前缀 + 物理名}，同数据源内唯一
     */
    public String canonicalPath() {
        return parentPath + "/" + pathPrefix(type) + "/" + physicalName;
    }

    /**
     * 规范化名（MySQL 按小写；由同步服务最终裁定，连接器提供默认）
     */
    public String normalizedName() {
        return physicalName == null ? "" : physicalName.toLowerCase();
    }

    private static String pathPrefix(ResourceType type) {
        return switch (type) {
            case DATABASE -> "db";
            case SCHEMA -> "schema";
            case TABLE -> "table";
            case VIEW -> "view";
            case MATERIALIZED_VIEW -> "mview";
            case COLUMN -> "col";
            case REDIS_DB -> "rdb";
            case KEY_PREFIX_POLICY -> "kpp";
            case DATA_SOURCE -> "ds";
        };
    }
}
