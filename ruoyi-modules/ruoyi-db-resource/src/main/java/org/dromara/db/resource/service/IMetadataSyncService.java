package org.dromara.db.resource.service;

import org.dromara.db.resource.domain.DbMetadataSyncJob;
import org.dromara.db.resource.domain.vo.DbResourceVo;

import java.util.List;

/**
 * 元数据同步服务（RES-005，docs/04 第 3.8 节）。
 *
 * <p>从目标引擎只读拉取目录快照，与 dbg_resource 对齐：
 * 新资源入库、已有资源更新 last_seen/metadata、消失资源标记 DROPPED。
 * 同步任务记录全量落库；失败路径审计独立事务留存。</p>
 *
 * @author DataGate
 */
public interface IMetadataSyncService {

    /**
     * 触发一次同步（手动）
     *
     * @param dataSourceId 数据源 ID
     * @return 同步任务记录（含计数与状态）
     */
    DbMetadataSyncJob syncNow(Long dataSourceId);

    /**
     * 查询数据源的最近同步任务
     */
    List<DbMetadataSyncJob> recentJobs(Long dataSourceId, int limit);

    /**
     * 查询数据源的资源目录（按父节点分层）
     *
     * @param dataSourceId 数据源 ID
     * @param parentId     父资源 ID，0 表示根
     */
    List<DbResourceVo> listResources(Long dataSourceId, Long parentId);
}
