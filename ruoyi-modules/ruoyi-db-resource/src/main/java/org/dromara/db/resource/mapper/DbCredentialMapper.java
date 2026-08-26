package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.vo.DbCredentialVo;

/**
 * 凭据 Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbCredentialMapper extends BaseMapperPlus<DbCredential, DbCredentialVo> {
}
