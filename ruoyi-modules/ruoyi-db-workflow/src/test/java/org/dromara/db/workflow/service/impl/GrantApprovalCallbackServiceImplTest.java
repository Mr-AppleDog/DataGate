package org.dromara.db.workflow.service.impl;

import org.dromara.db.auth.domain.Grant;
import org.dromara.db.auth.service.GrantAdminService;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.GrantSourceType;
import org.dromara.db.core.enums.SubjectType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.db.workflow.domain.GrantApplication;
import org.dromara.db.workflow.repository.GrantApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审批回调单元测试（纯桩）。
 */
@Tag("unit")
class GrantApprovalCallbackServiceImplTest {

    private StubApplicationRepository applicationRepo;
    private StubGrantAdminService grantAdminService;
    private GrantApprovalCallbackServiceImpl callback;

    @BeforeEach
    void setUp() {
        applicationRepo = new StubApplicationRepository();
        grantAdminService = new StubGrantAdminService();
        callback = new GrantApprovalCallbackServiceImpl(applicationRepo, grantAdminService);
    }

    private GrantApplication newPendingApp() {
        GrantApplication app = new GrantApplication();
        app.setId(1L);
        app.setApplicantId(100L);
        app.setSubjectType(SubjectType.USER);
        app.setSubjectId(100L);
        app.setResourceId(5L);
        app.setAction(DbAction.QUERY);
        app.setEffect(GrantEffect.ALLOW);
        app.setConditions(new HashMap<>());
        app.setEffectiveAt(Instant.now());
        app.setExpiresAt(Instant.now().plusSeconds(3600));
        app.setReason("需要查询 orders");
        app.setStatus("PENDING");
        return app;
    }

    @Test
    void approvalCreatesGrantAndMarksApproved() {
        applicationRepo.save(newPendingApp());
        callback.onApproval(1L, 7L);

        assertEquals(1, grantAdminService.created.size());
        Grant g = grantAdminService.created.get(0);
        assertEquals(5L, g.getResourceId());
        assertEquals(DbAction.QUERY, g.getAction());
        assertEquals(GrantSourceType.REQUEST, g.getSourceType());
        assertEquals(1L, g.getSourceId(), "sourceId 应关联申请单（幂等键）");
        assertEquals(7L, grantAdminService.createdActor.get(0));

        assertEquals("APPROVED", applicationRepo.byId.get(1L).getStatus());
        assertNotNull(applicationRepo.byId.get(1L).getGrantId());
    }

    @Test
    void rejectionDoesNotCreateGrant() {
        applicationRepo.save(newPendingApp());
        callback.onRejection(1L, 7L, "理由不充分");
        assertTrue(grantAdminService.created.isEmpty(), "拒绝不应生成授权");
        assertEquals("REJECTED", applicationRepo.byId.get(1L).getStatus());
        assertNull(applicationRepo.byId.get(1L).getGrantId());
    }

    @Test
    void approvalIdempotentOnAlreadyApproved() {
        applicationRepo.save(newPendingApp());
        callback.onApproval(1L, 7L);
        int first = grantAdminService.created.size();
        callback.onApproval(1L, 7L); // 再次回调
        assertEquals(first, grantAdminService.created.size(), "已批准不应重复生成授权");
    }

    @Test
    void approvalUnknownApplicationNoOp() {
        callback.onApproval(99999L, 7L);
        assertTrue(grantAdminService.created.isEmpty());
    }

    // ================= 桩 =================

    static class StubApplicationRepository implements GrantApplicationRepository {
        final Map<Long, GrantApplication> byId = new HashMap<>();
        void save(GrantApplication a) { byId.put(a.getId(), a); }
        @Override public GrantApplication findById(Long id) { return byId.get(id); }
        @Override public Long insert(GrantApplication application) { byId.put(application.getId(), application); return application.getId(); }
        @Override public boolean updateApprovalResult(Long id, String status, Long grantId, Long approverId) {
            GrantApplication a = byId.get(id);
            if (a == null) return false;
            a.setStatus(status);
            a.setGrantId(grantId);
            a.setApproverId(approverId);
            return true;
        }
        @Override public boolean updateFlowInstanceId(Long id, Long flowInstanceId) { return true; }
        @Override public boolean updateStatus(Long id, String status) {
            GrantApplication a = byId.get(id);
            if (a != null) a.setStatus(status);
            return true;
        }
        @Override public Page<GrantApplication> page(Long applicantId, PageQuery pageQuery) {
            Page<GrantApplication> p = new Page<>();
            java.util.List<GrantApplication> all = new java.util.ArrayList<>(byId.values());
            if (applicantId != null) all.removeIf(a -> !applicantId.equals(a.getApplicantId()));
            p.setRecords(all);
            p.setTotal(all.size());
            return p;
        }
    }

    static class StubGrantAdminService implements GrantAdminService {
        final java.util.List<Grant> created = new java.util.ArrayList<>();
        final java.util.List<Long> createdActor = new java.util.ArrayList<>();
        private final AtomicLong seq = new AtomicLong(1000);
        @Override public Long createGrant(Grant grant, Long actorId) {
            grant.setId(seq.incrementAndGet());
            created.add(grant);
            createdActor.add(actorId);
            return grant.getId();
        }
        @Override public boolean revokeGrant(Long grantId, Long actorId) { return false; }
    }
}
