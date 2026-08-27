package org.dromara.db.executor.support;

import java.util.List;

/**
 * 资源路径解析端口（docs/03 第 3 节资源模型）。
 *
 * <p>将方言解析器输出的规范资源路径（如 {@code /db/mydb/table/t}）解析为
 * 资源目录中的资源 ID，供 {@code AuthorizationDecisionService.decide} 鉴权。
 * 实现由 db-resource 提供（资源目录 dbg_resource）。</p>
 *
 * <p>缺省（未注入）时网关对任何带资源路径的请求失败关闭——
 * 无法把路径解析为可鉴权资源即拒绝，绝不放行未鉴权资源。</p>
 *
 * @author DataGate
 */
public interface ResourcePathResolver {

    /**
     * 批量解析资源路径为资源 ID。
     *
     * @param dataSourceId  数据源（限定目录范围）
     * @param defaultDatabase 连接默认库（用于补全未限定库名的路径）
     * @param canonicalPaths 解析器输出的规范路径列表
     * @return 解析出的资源 ID 列表（顺序与入参对应；未解析到的项返回 null，调用方据 size 判定失败关闭）
     */
    List<Long> resolve(Long dataSourceId, String defaultDatabase, List<String> canonicalPaths);
}
