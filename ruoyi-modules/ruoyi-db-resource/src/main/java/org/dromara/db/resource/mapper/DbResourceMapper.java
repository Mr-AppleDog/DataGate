package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.domain.vo.DbResourceVo;

/**
 * 资源目录 Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbResourceMapper extends BaseMapperPlus<DbResource, DbResourceVo> {
}
