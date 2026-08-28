package org.dromara.db.workflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.workflow.domain.DbExportJob;
import org.dromara.db.workflow.domain.vo.DbExportJobVo;

/**
 * 导出工单 Mapper（docs/04 §5.6）。
 *
 * @author DataGate
 */
@Mapper
public interface DbExportJobMapper extends BaseMapperPlus<DbExportJob, DbExportJobVo> {
}
