package org.dromara.db.resource.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.db.core.spi.ResourcePathResolver;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源路径解析实现（docs/03 第 3 节、docs/04 §3.6 dbg_resource）。
 *
 * <p>按数据源 + canonicalPath 批量查询资源目录，只返回 ACTIVE 资源；
 * DISABLED/DROPPED 不入结果（调用方见 null 即失败关闭）。
 * 路径补全（未限定库名）由编排器在调用前完成。</p>
 *
 * @author DataGate
 */
@Service
@RequiredArgsConstructor
public class DbResourcePathResolver implements ResourcePathResolver {

    private final DbResourceMapper resourceMapper;

    @Override
    public List<Long> resolve(Long dataSourceId, String defaultDatabase, List<String> canonicalPaths) {
        if (canonicalPaths == null || canonicalPaths.isEmpty()) {
            return List.of();
        }
        List<DbResource> found = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .eq(DbResource::getDataSourceId, dataSourceId)
            .in(DbResource::getCanonicalPath, canonicalPaths)
            .eq(DbResource::getStatus, "ACTIVE"));
        Map<String, Long> byPath = new HashMap<>(found.size());
        for (DbResource r : found) {
            byPath.put(r.getCanonicalPath(), r.getId());
        }
        List<Long> result = new ArrayList<>(canonicalPaths.size());
        for (String p : canonicalPaths) {
            result.add(byPath.get(p));
        }
        return result;
    }
}
