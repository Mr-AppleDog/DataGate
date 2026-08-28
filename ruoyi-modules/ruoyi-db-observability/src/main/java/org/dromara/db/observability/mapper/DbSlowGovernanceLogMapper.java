package org.dromara.db.observability.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.observability.domain.DbSlowGovernanceLog;

/**
 * DbSlowGovernanceLog Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbSlowGovernanceLogMapper extends BaseMapperPlus<DbSlowGovernanceLog, DbSlowGovernanceLog> {
}
