package org.dromara.db.workflow.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.db.workflow.domain.GrantApplication;

/**
 * 申请单 Mapper（M2-02）。
 *
 * @author DataGate
 */
@Mapper
public interface GrantApplicationMapper extends BaseMapperPlus<GrantApplication, GrantApplication> {
}
