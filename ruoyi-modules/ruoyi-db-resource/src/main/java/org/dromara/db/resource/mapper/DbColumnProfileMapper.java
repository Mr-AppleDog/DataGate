package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbColumnProfile;
import org.dromara.db.resource.domain.vo.DbColumnProfileVo;

/**
 * 列敏感策略 Mapper（docs/04 §3.7）。
 *
 * @author DataGate
 */
@Mapper
public interface DbColumnProfileMapper extends BaseMapperPlus<DbColumnProfile, DbColumnProfileVo> {
}
