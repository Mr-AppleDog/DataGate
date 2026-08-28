package org.dromara.db.alert.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.alert.domain.DbAlertRule;

/**
 * DbAlertRule Mapper
 *
 * @author DataGate
 */
@Mapper
public interface DbAlertRuleMapper extends BaseMapperPlus<DbAlertRule, DbAlertRule> {
}
