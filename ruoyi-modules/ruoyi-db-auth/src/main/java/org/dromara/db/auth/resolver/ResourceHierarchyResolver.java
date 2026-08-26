package org.dromara.db.auth.resolver;

import java.util.List;

/**
 * 资源层级解析端口（docs/03 第 5.1 节资源继承、第 7.2 节 step 3/4，RES）。
 *
 * <p>实现由 db-resource 模块提供（持有资源树）；db-auth 不直接依赖 db-resource，
 * 通过 Spring 可选注入接入。返回值包含资源自身及其全部祖先 ID，用于在“资源+祖先”上加载候选授权。
 * 返回空或 null 表示资源不可解析（不存在/已下线），鉴权服务据此失败关闭。</p>
 *
 * <p>实现约定：DISABLED/DROPPED 资源不应出现在结果中（由 db-resource impl 保证）。
 * 无实现注入时，鉴权服务回退为只查资源自身（不实现继承，已在报告中标注）。</p>
 *
 * @author DataGate
 */
public interface ResourceHierarchyResolver {

    /**
     * 解析资源自身及全部祖先 ID（自身在前）。
     *
     * @param resourceId 目标资源 ID
     * @return 自身+祖先 ID 列表；资源不可解析时返回空列表或 null
     */
    List<Long> resolveAncestors(Long resourceId);
}
