package org.dromara.db.auth.service.impl;

import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.policy.PolicyCacheInvalidationHook;
import org.dromara.db.auth.policy.PolicyVersionSource;
import org.dromara.db.auth.repository.GrantWriteRepository;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.SubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 授权管理服务单元测试（纯桩）。
 */
@Tag("unit")
class GrantAdminServiceImplTest {

    private StubVersionSource versionSource;
    private StubInvalidationHook hook;
    private StubWriteRepository writeRepo;
    private GrantAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        versionSource = new StubVersionSource();
        hook = new StubInvalidationHook();
        writeRepo = new StubWriteRepository();
        service = new GrantAdminServiceImpl(writeRepo, versionSource, hook);
    }

    private Grant newGrant() {
        Grant g = new Grant();
        g.setSubjectType(SubjectType.USER);
        g.setSubjectId(100L);
        g.setResourceId(1L);
        g.setAction(DbAction.QUERY);
        g.setEffect(GrantEffect.ALLOW);
        g.setConditions(new HashMap<>());
        g.setSourceType(GrantSourceType.REQUEST);
        g.setSourceId(9001L);
        g.setEffectiveAt(Instant.now());
        g.setExpiresAt(Instant.now().plusSeconds(3600));
        return g;
    }

    @Test
    void createGrantsBumpsVersionAndBroadcasts() {
        versionSource.set(41);
        Long id = service.createGrant(newGrant(), 7L);
        assertNotNull(id);
        Grant persisted = writeRepo.byId.get(id);
        assertNotNull(persisted);
        assertEquals(42L, persisted.getPolicyVersion(), "版本应为当前+1");
        assertEquals(7L, persisted.getCreateBy());
        assertEquals(1, hook.changedVersions.size());
        assertEquals(42L, hook.changedVersions.get(0));
    }

    @Test
    void revokeSetsRevokedAtBumpsAndBroadcasts() {
        versionSource.set(41);
        Long id = service.createGrant(newGrant(), 7L);
        versionSource.set(42); // create 后当前版本已是 42
        boolean ok = service.revokeGrant(id, 8L);
        assertTrue(ok);
        Grant g = writeRepo.byId.get(id);
        assertNotNull(g.getRevokedAt());
        assertEquals(43L, g.getPolicyVersion());
        assertEquals(8L, g.getUpdateBy());
        assertTrue(hook.changedVersions.contains(43L));
    }

    @Test
    void revokeIdempotentDoesNotDoubleBump() {
        versionSource.set(41);
        Long id = service.createGrant(newGrant(), 7L);
        versionSource.set(42);
        assertTrue(service.revokeGrant(id, 8L));
        int broadcastsBefore = hook.changedVersions.size();
        assertFalse(service.revokeGrant(id, 8L), "已撤销应幂等返回 false");
        assertEquals(broadcastsBefore, hook.changedVersions.size(), "幂等不应再次广播");
    }

    @Test
    void revokeUnknownGrantReturnsFalse() {
        assertFalse(service.revokeGrant(99999L, 8L));
    }

    @Test
    void cannotCreateAlreadyRevokedGrant() {
        Grant g = newGrant();
        g.setRevokedAt(Instant.now());
        assertThrows(IllegalArgumentException.class, () -> service.createGrant(g, 7L));
    }

    // ================= 桩 =================

    static class StubVersionSource implements PolicyVersionSource {
        private final AtomicLong v = new AtomicLong(0);
        void set(long x) { v.set(x); }
        @Override public long currentVersion() { return v.get(); }
    }

    static class StubInvalidationHook implements PolicyCacheInvalidationHook {
        final List<Long> changedVersions = new ArrayList<>();
        @Override public void onPolicyChanged(long newVersion) { changedVersions.add(newVersion); }
        @Override public void onUserInvalidated(Long actorId) { }
    }

    static class StubWriteRepository implements GrantWriteRepository {
        long seq = 1;
        final Map<Long, Grant> byId = new HashMap<>();
        @Override public Long insert(Grant grant) {
            long id = seq++;
            grant.setId(id);
            byId.put(id, grant);
            return id;
        }
        @Override public Grant findById(Long id) { return byId.get(id); }
        @Override public boolean updateRevoked(Long id, Instant revokedAt, Long policyVersion, Long updateBy) {
            Grant g = byId.get(id);
            if (g == null) return false;
            g.setRevokedAt(revokedAt);
            g.setPolicyVersion(policyVersion);
            g.setUpdateBy(updateBy);
            return true;
        }
    }
}
