package org.dromara.db.workflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * flow_task 待办任务查询（轻量，不引入 warm-flow 依赖，M2-02）。
 *
 * <p>approve 需要当前审批节点的 taskId 以办理；db-workflow 不依赖 ruoyi-workflow
 * 模块，故用原生 SQL 按 instanceId + nodeCode 查待办 task。</p>
 *
 * @author DataGate
 */
@Mapper
public interface FlowTaskQueryMapper {

    /**
     * 按流程实例 ID 与节点编码查未删除的待办任务 ID。
     *
     * @param instanceId 流程实例 ID
     * @param nodeCode   节点编码（approve）
     * @return task id，不存在返回 null
     */
    @Select("SELECT id FROM flow_task WHERE instance_id = #{instanceId} "
        + "AND node_code = #{nodeCode} AND del_flag = '0' LIMIT 1")
    Long selectTaskId(Long instanceId, String nodeCode);
}
