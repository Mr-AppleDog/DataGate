package org.dromara.db.observability.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.observability.domain.DbSlowBucket;

/**
 * DbSlowBucket Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbSlowBucketMapper extends BaseMapperPlus<DbSlowBucket, DbSlowBucket> {
}
