package org.dromara.db.alert.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.alert.domain.DbAlertEvent;

/**
 * DbAlertEvent Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbAlertEventMapper extends BaseMapperPlus<DbAlertEvent, DbAlertEvent> {
}
