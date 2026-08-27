package org.dromara.db.core.spi;

import java.util.List;

/**
 * 资源路径解析端口（docs/03 第 3 节资源模型、docs/06 §4 step 5）。
 *
 * <p>将方言解析器输出的规范资源路径（如 {@code /db/mydb/table/orders}）解析为
 * 资源目录中的资源 ID，供 {@link org.dromara.db.core.authz.AuthorizationDecisionService}
 * 鉴权。实现由 db-resource 提供（资源目录 dbg_resource，docs/04 §3.6）。</p>
 *
 * <p>平台级端口（置于 db-core，避免 db-executor↔db-resource 循环依赖）。
 * 缺省（未注入）时消费方对带资源路径的请求失败关闭——
 * 无法把路径解析为可鉴权资源即拒绝，绝不放行未鉴权资源。</p>
 *
 * @author DataGate
 */
public interface ResourcePathResolver {

    /**
     * 批量解析资源路径为资源 ID。
     *
     * @param dataSourceId   数据源（限定目录范围）
     * @param defaultDatabase 连接默认库（用于补全未限定库名的路径）
     * @param canonicalPaths 解析器输出的规范路径列表
     * @return 解析出的资源 ID 列表（顺序与入参对应；未解析到的项返回 null，
     *         调用方据 size/contains(null) 判定失败关闭）；DISABLED/DROPPED 不入结果
     */
    List<Long> resolve(Long dataSourceId, String defaultDatabase, List<String> canonicalPaths);
}
