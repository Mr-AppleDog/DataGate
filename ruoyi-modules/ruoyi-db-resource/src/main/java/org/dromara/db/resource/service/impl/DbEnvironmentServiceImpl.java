package org.dromara.db.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.db.resource.domain.DbEnvironment;
import org.dromara.db.resource.domain.vo.DbEnvironmentVo;
import org.dromara.db.resource.mapper.DbEnvironmentMapper;
import org.dromara.db.resource.service.IDbEnvironmentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 环境管理实现
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class DbEnvironmentServiceImpl implements IDbEnvironmentService {

    private final DbEnvironmentMapper environmentMapper;

    @Override
    public List<DbEnvironmentVo> listActive() {
        return environmentMapper.selectVoList(new LambdaQueryWrapper<DbEnvironment>()
            .eq(DbEnvironment::getStatus, "ACTIVE")
            .orderByAsc(DbEnvironment::getId));
    }
}
