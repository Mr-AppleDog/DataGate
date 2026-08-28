package org.dromara.db.alert.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.alert.domain.DbNotificationChannel;

/**
 * DbNotificationChannel Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbNotificationChannelMapper extends BaseMapperPlus<DbNotificationChannel, DbNotificationChannel> {
}
