package org.dromara.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.system.domain.DbUserSecurity;

/**
 * 用户安全状态 Mapper
 *
 * @author DataGate
 */
@Mapper
public interface SysUserSecurityMapper extends BaseMapperPlus<DbUserSecurity, DbUserSecurity> {
}
