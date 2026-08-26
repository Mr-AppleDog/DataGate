package org.dromara.db.auth;

import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.repository.GrantRepository;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.SubjectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 内存授权仓库（测试用，镜像 {@code GrantRepositoryImpl} 的结构匹配语义）。
 *
 * @author DataGate
 */
public class InMemoryGrantRepository implements GrantRepository {

    private final List<Grant> store = new ArrayList<>();

    public InMemoryGrantRepository add(Grant g) {
        store.add(g);
        return this;
    }

    @Override
    public List<Grant> findCandidates(List<Long> resourceIds, DbAction action,
                                      Long actorId, Set<Long> deptIds, Set<Long> groupIds) {
        if (resourceIds == null || resourceIds.isEmpty() || action == null) {
            return List.of();
        }
        Set<Long> depts = deptIds == null ? Set.of() : deptIds;
        Set<Long> groups = groupIds == null ? Set.of() : groupIds;
        List<Grant> out = new ArrayList<>();
        for (Grant g : store) {
            if (!isActiveRow(g)) {
                continue;
            }
            if (!resourceIds.contains(g.getResourceId())) {
                continue;
            }
            if (g.getAction() != action) {
                continue;
            }
            if (!subjectMatches(g, actorId, depts, groups)) {
                continue;
            }
            out.add(g);
        }
        return out;
    }

    @Override
    public long maxPolicyVersion() {
        long max = 0L;
        for (Grant g : store) {
            if (!isActiveRow(g)) {
                continue;
            }
            if (g.getPolicyVersion() != null && g.getPolicyVersion() > max) {
                max = g.getPolicyVersion();
            }
        }
        return max;
    }

    private static boolean isActiveRow(Grant g) {
        String del = g.getDelFlag();
        return del == null || "0".equals(del);
    }

    private static boolean subjectMatches(Grant g, Long actorId, Set<Long> depts, Set<Long> groups) {
        SubjectType t = g.getSubjectType();
        if (t == SubjectType.USER) {
            return actorId != null && actorId.equals(g.getSubjectId());
        }
        if (t == SubjectType.DEPT) {
            return depts.contains(g.getSubjectId());
        }
        if (t == SubjectType.GROUP) {
            return groups.contains(g.getSubjectId());
        }
        return false;
    }
}
