package org.dromara.db.resource.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.resource.domain.DbMetadataSyncJob;

/**
 * 元数据同步任务 Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbMetadataSyncJobMapper extends BaseMapperPlus<DbMetadataSyncJob, DbMetadataSyncJob> {
}
