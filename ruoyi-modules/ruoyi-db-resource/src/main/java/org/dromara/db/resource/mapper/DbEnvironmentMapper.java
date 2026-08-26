package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbEnvironment;

/**
 * 环境 Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbEnvironmentMapper extends BaseMapperPlus<DbEnvironment, DbEnvironment> {
}
