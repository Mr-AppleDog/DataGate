package org.dromara.db.resource.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.dromara.db.core.spi.ColumnMaskingPolicyResolver;
import org.dromara.db.resource.domain.DbColumnProfile;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.mapper.DbColumnProfileMapper;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列脱敏策略解析实现（docs/04 §3.7、docs/06 §11、docs/10 M5-05）。
 *
 * <p>resolve：按 COLUMN 资源 ID 批量查 dbg_column_profile 转 ColumnMaskingPolicy。
 * resolveByTableColumn：按表资源查所有 COLUMN 子资源，含未标注列默认 PUBLIC/NONE 策略，
 * 键=(表物理名.列名).toLowerCase，供执行器 JDBC 基列名 lineage 匹配。</p>
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class DbColumnMaskingPolicyResolver implements ColumnMaskingPolicyResolver {

    private static final String RESOURCE_TYPE_COLUMN = "COLUMN";

    private final DbColumnProfileMapper profileMapper;
    private final DbResourceMapper resourceMapper;

    @Override
    public Map<Long, ColumnMaskingPolicy> resolve(Collection<Long> columnResourceIds) {
        Map<Long, ColumnMaskingPolicy> out = new HashMap<>();
        if (columnResourceIds == null || columnResourceIds.isEmpty()) {
            return out;
        }
        List<DbColumnProfile> rows = profileMapper.selectList(new LambdaQueryWrapper<DbColumnProfile>()
            .in(DbColumnProfile::getResourceId, columnResourceIds));
        for (DbColumnProfile p : rows) {
            ColumnMaskingPolicy policy = ColumnProfileConverter.toPolicy(p);
            if (policy != null) {
                out.put(p.getResourceId(), policy);
            }
        }
        return out;
    }

    @Override
    public Map<String, ColumnMaskingPolicy> resolveByTableColumn(Collection<Long> tableResourceIds) {
        Map<String, ColumnMaskingPolicy> out = new HashMap<>();
        if (tableResourceIds == null || tableResourceIds.isEmpty()) {
            return out;
        }
        // 表资源 -> 物理名（JDBC getTableName 匹配 physicalName）
        List<DbResource> tables = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .in(DbResource::getId, tableResourceIds));
        Map<Long, String> tablePhysical = new HashMap<>();
        for (DbResource t : tables) {
            tablePhysical.put(t.getId(), t.getPhysicalName() == null ? "" : t.getPhysicalName());
        }
        // COLUMN 子资源
        List<DbResource> cols = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .in(DbResource::getParentId, tableResourceIds)
            .eq(DbResource::getResourceType, RESOURCE_TYPE_COLUMN)
            .eq(DbResource::getStatus, "ACTIVE"));
        if (cols.isEmpty()) {
            return out;
        }
        List<Long> colIds = cols.stream().map(DbResource::getId).toList();
        Map<Long, ColumnMaskingPolicy> labeled = resolve(colIds);
        for (DbResource c : cols) {
            String tname = tablePhysical.getOrDefault(c.getParentId(), "");
            String cname = c.getNormalizedName() == null ? "" : c.getNormalizedName();
            String key = (tname + "." + cname).toLowerCase();
            ColumnMaskingPolicy policy = labeled.get(c.getId());
            if (policy == null) {
                // 未标注列默认非敏感（PUBLIC/NONE）——直接引用不误隐藏；仅真正未知来源才由执行器 HIDDEN
                policy = new ColumnMaskingPolicy(c.getId(), cname, SensitivityLevel.PUBLIC, MaskingType.NONE, null, null);
            }
            out.put(key, policy);
        }
        return out;
    }
}
