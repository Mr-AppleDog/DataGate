package org.dromara.db.workflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.workflow.domain.DbChangeOrder;
import org.dromara.db.workflow.domain.vo.DbChangeOrderVo;

/**
 * 变更工单 Mapper（docs/04 §5.7）。
 *
 * @author DataGate
 */
@Mapper
public interface DbChangeOrderMapper extends BaseMapperPlus<DbChangeOrder, DbChangeOrderVo> {
}
