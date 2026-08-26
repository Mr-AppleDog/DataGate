package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbCredentialVersion;

/**
 * 凭据版本 Mapper（只追加新版本；密文字段永不更新）
 *
 * @author DataGate
 */
@Mapper
public interface DbCredentialVersionMapper extends BaseMapperPlus<DbCredentialVersion, DbCredentialVersion> {
}
