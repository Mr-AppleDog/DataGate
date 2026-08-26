package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.domain.vo.DbDataSourceVo;

/**
 * 数据源 Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbDataSourceMapper extends BaseMapperPlus<DbDataSource, DbDataSourceVo> {
}
