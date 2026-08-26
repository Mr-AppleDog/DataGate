package org.dromara.db.auth;

import org.dromara.db.auth.resolver.ResourceHierarchyResolver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源层级解析桩（测试用）：按 resourceId 返回预设的“自身+祖先”链。
 *
 * @author DataGate
 */
public class StubHierarchyResolver implements ResourceHierarchyResolver {

    private final Map<Long, List<Long>> chains = new HashMap<>();

    public StubHierarchyResolver put(Long resourceId, List<Long> selfAndAncestors) {
        chains.put(resourceId, selfAndAncestors);
        return this;
    }

    @Override
    public List<Long> resolveAncestors(Long resourceId) {
        return chains.getOrDefault(resourceId, List.of());
    }
}
