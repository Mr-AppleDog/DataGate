package org.dromara.db.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.resource.domain.DbColumnProfile;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.domain.vo.DbColumnProfileVo;
import org.dromara.db.resource.mapper.DbColumnProfileMapper;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.dromara.db.resource.support.ColumnProfileConverter;
import org.dromara.db.resource.service.IColumnProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列敏感策略管理实现（docs/04 §3.7、docs/10 M5-05）。
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColumnProfileServiceImpl implements IColumnProfileService {

    private static final String RESOURCE_TYPE_COLUMN = "COLUMN";

    private final DbColumnProfileMapper profileMapper;
    private final DbResourceMapper resourceMapper;

    @Override
    public DbColumnProfileVo get(Long resourceId) {
        DbColumnProfile p = profileMapper.selectById(resourceId);
        DbColumnProfileVo vo = toVo(p, (DbResource) null);
        if (vo.getResourceId() == null) {
            vo.setResourceId(resourceId);
        }
        return vo;
    }

    @Override
    public List<DbColumnProfileVo> listByTable(Long tableResourceId) {
        List<DbResource> cols = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .eq(DbResource::getParentId, tableResourceId)
            .eq(DbResource::getResourceType, RESOURCE_TYPE_COLUMN)
            .eq(DbResource::getStatus, "ACTIVE"));
        if (cols.isEmpty()) {
            return List.of();
        }
        List<Long> ids = cols.stream().map(DbResource::getId).toList();
        List<DbColumnProfile> rows = profileMapper.selectList(new LambdaQueryWrapper<DbColumnProfile>()
            .in(DbColumnProfile::getResourceId, ids));
        Map<Long, DbColumnProfile> byId = new HashMap<>();
        for (DbColumnProfile r : rows) {
            byId.put(r.getResourceId(), r);
        }
        List<DbColumnProfileVo> out = new ArrayList<>(cols.size());
        for (DbResource c : cols) {
            out.add(toVo(byId.get(c.getId()), c));
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setManualLabel(Long resourceId, String sensitivityLevel, String maskingType, String maskingConfig) {
        DbResource res = resourceMapper.selectById(resourceId);
        if (res == null || !RESOURCE_TYPE_COLUMN.equals(res.getResourceType())) {
            throw new ServiceException("目标资源不是列（COLUMN），无法设置脱敏标签");
        }
        DbColumnProfile existing = profileMapper.selectById(resourceId);
        DbColumnProfile p = existing != null ? existing : new DbColumnProfile();
        p.setResourceId(resourceId);
        p.setSensitivityLevel(sensitivityLevel);
        p.setMaskingType(maskingType);
        p.setMaskingConfig(maskingConfig);
        p.setClassificationSource("MANUAL");
        p.setConfirmedBy(LoginHelper.getUserId());
        p.setConfirmedAt(new Date());
        if (existing == null) {
            profileMapper.insert(p);
        } else {
            profileMapper.updateById(p);
        }
        log.info("列脱敏标签已人工确认 resourceId={} level={} type={} by={}", resourceId, sensitivityLevel, maskingType, p.getConfirmedBy());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int applyRuleLabels(List<DbColumnProfile> ruleProfiles) {
        if (ruleProfiles == null || ruleProfiles.isEmpty()) {
            return 0;
        }
        List<Long> ids = ruleProfiles.stream().map(DbColumnProfile::getResourceId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        List<DbColumnProfile> existing = profileMapper.selectList(new LambdaQueryWrapper<DbColumnProfile>()
            .in(DbColumnProfile::getResourceId, ids));
        Map<Long, DbColumnProfile> byId = new HashMap<>();
        for (DbColumnProfile e : existing) {
            byId.put(e.getResourceId(), e);
        }
        int applied = 0;
        for (DbColumnProfile rule : ruleProfiles) {
            if (rule.getResourceId() == null) {
                continue;
            }
            DbColumnProfile cur = byId.get(rule.getResourceId());
            // MANUAL 不被重同步覆盖（docs/04 §3.7、docs/10 M5-05）
            if (ColumnProfileConverter.shouldPreserveManual(cur)) {
                continue;
            }
            rule.setClassificationSource("RULE");
            if (cur == null) {
                profileMapper.insert(rule);
            } else {
                profileMapper.updateById(rule);
            }
            applied++;
        }
        return applied;
    }

    private DbColumnProfileVo toVo(DbColumnProfile p, DbResource col) {
        DbColumnProfileVo vo = new DbColumnProfileVo();
        if (p != null) {
            vo.setResourceId(p.getResourceId());
            vo.setSensitivityLevel(p.getSensitivityLevel());
            vo.setMaskingType(p.getMaskingType());
            vo.setMaskingConfig(p.getMaskingConfig());
            vo.setClassificationSource(p.getClassificationSource());
            vo.setConfirmedBy(p.getConfirmedBy());
            vo.setConfirmedAt(p.getConfirmedAt());
        }
        if (col != null) {
            vo.setColumnName(col.getNormalizedName());
            vo.setCanonicalPath(col.getCanonicalPath());
            vo.setDataSourceId(col.getDataSourceId());
            if (vo.getResourceId() == null) {
                vo.setResourceId(col.getId());
            }
        }
        return vo;
    }

}
