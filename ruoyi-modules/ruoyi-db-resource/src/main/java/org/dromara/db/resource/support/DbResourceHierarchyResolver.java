package org.dromara.db.resource.support;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.auth.resolver.ResourceHierarchyResolver;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 资源层级解析实现（docs/03 §5.1 资源继承、§7.2 step 3/4，M2-02 接通）。
 *
 * <p>按 {@code dbg_resource.parent_id} 递归向上查祖先链，返回"自身在前 + 全部祖先"。
 * 用于授权引擎在"资源 + 祖先"上加载候选授权（DATABASE 级 Grant 继承到 TABLE 查询）。
 * DISABLED/DROPPED 资源不入结果：自身非 ACTIVE → 返回空（失败关闭）；
 * 中间祖先非 ACTIVE → 止于其下（不加入该 DISABLED 及以上）。</p>
 *
 * <p>循环防护（visited + 最大深度 16，覆盖 DATA_SOURCE→DATABASE→SCHEMA→TABLE→COLUMN 层级）。
 * 每层一次 selectById；资源层级浅（通常 3-5），可接受。缓存留待后续优化。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbResourceHierarchyResolver implements ResourceHierarchyResolver {

    private static final int MAX_DEPTH = 16;
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final DbResourceMapper resourceMapper;

    @Override
    public List<Long> resolveAncestors(Long resourceId) {
        if (resourceId == null) {
            return List.of();
        }
        List<Long> chain = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Long current = resourceId;
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            if (!visited.add(current)) {
                log.warn("资源层级检测到环，已截断：resourceId={}, chain={}", resourceId, chain);
                break;
            }
            DbResource r = resourceMapper.selectById(current);
            if (r == null) {
                // 节点不存在：若链非空（祖先已收集）则止于此；若链空（自身不存在）→ 失败关闭返回空
                return chain.isEmpty() ? List.of() : chain;
            }
            if (!STATUS_ACTIVE.equals(r.getStatus())) {
                // 非 ACTIVE：自身非 ACTIVE → 不可解析返回空；祖先非 ACTIVE → 止于此不加入
                return chain.isEmpty() ? List.of() : chain;
            }
            chain.add(current);
            current = r.getParentId();
            depth++;
        }
        return chain;
    }
}
