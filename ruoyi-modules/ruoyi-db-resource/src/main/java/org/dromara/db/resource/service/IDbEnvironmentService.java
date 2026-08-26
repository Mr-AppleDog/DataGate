package org.dromara.db.resource.service;

import org.dromara.db.resource.domain.vo.DbEnvironmentVo;

import java.util.List;

/**
 * 环境管理服务（RES-001）
 *
 * @author DataGate
 */
public interface IDbEnvironmentService {

    /**
     * 查询全部启用中的环境（数据源表单下拉用）
     */
    List<DbEnvironmentVo> listActive();
}
