package org.dromara.db.observability.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.observability.domain.DbSlowFingerprint;

/**
 * DbSlowFingerprint Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbSlowFingerprintMapper extends BaseMapperPlus<DbSlowFingerprint, DbSlowFingerprint> {
}
