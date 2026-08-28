package org.dromara.db.workflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.workflow.domain.DbEmergencyAccess;
import org.dromara.db.workflow.domain.vo.DbEmergencyAccessVo;

/**
 * 紧急访问 Mapper（docs/03 §10.4）。
 *
 * @author DataGate
 */
@Mapper
public interface DbEmergencyAccessMapper extends BaseMapperPlus<DbEmergencyAccess, DbEmergencyAccessVo> {
}
