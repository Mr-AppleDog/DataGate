package org.dromara.db.workflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.workflow.domain.DbChangeExecution;
import org.dromara.db.workflow.domain.vo.DbChangeExecutionVo;

/**
 * 变更执行 Mapper（docs/04 §5.7）。
 *
 * @author DataGate
 */
@Mapper
public interface DbChangeExecutionMapper extends BaseMapperPlus<DbChangeExecution, DbChangeExecutionVo> {
}
