package org.dromara.system.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.system.domain.DbUserTotp;

/**
 * 用户 TOTP Mapper
 *
 * @author DataGate
 */
@Mapper
public interface SysUserTotpMapper extends BaseMapperPlus<DbUserTotp, DbUserTotp> {
}
