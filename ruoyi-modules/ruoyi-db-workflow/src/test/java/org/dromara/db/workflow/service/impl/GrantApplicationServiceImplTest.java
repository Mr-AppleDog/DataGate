package org.dromara.db.workflow.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.WorkflowService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.GrantEffect;
import org.dromara.db.core.enums.SubjectType;
import org.dromara.db.workflow.domain.GrantApplication;
import org.dromara.db.workflow.domain.bo.GrantApplyBo;
import org.dromara.db.workflow.domain.bo.GrantApproveBo;
import org.dromara.db.workflow.mapper.FlowTaskQueryMapper;
import org.dromara.db.workflow.repository.GrantApplicationRepository;
import org.dromara.db.workflow.service.GrantApprovalCallbackService;
import org.dromara.workflow.service.IFlwTaskService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * 申请单服务单测（M2-02）：自批拒绝、非指定审批人拒绝（docs/03 §13 #9）。
 * 编排与 WarmFlow 的真实交互由端到端冒烟覆盖。
 */
@Tag("unit")
class GrantApplicationServiceImplTest {

    private static GrantApplicationServiceImpl newSvc(GrantApplicationRepository repo,
                                                      WorkflowService wf,
                                                      FlowTaskQueryMapper taskMapper,
                                                      GrantApprovalCallbackService cb) {
        return new GrantApplicationServiceImpl(repo, wf, taskMapper, cb, org.mockito.Mockito.mock(IFlwTaskService.class));
    }

    private GrantApplyBo applyBo(Long approverId) {
        GrantApplyBo bo = new GrantApplyBo();
        bo.setApproverId(approverId);
        bo.setSubjectType(SubjectType.USER);
        bo.setSubjectId(100L);
        bo.setResourceId(5L);
        bo.setAction(DbAction.QUERY);
        bo.setEffect(GrantEffect.ALLOW);
        bo.setConditions(new java.util.HashMap<>());
        bo.setExpiresAt(Instant.now().plusSeconds(3600));
        bo.setReason("需要查询 orders");
        return bo;
    }

    private GrantApplication pendingApp(Long applicantId, Long approverId) {
        GrantApplication app = new GrantApplication();
        app.setId(1L);
        app.setApplicantId(applicantId);
        app.setApproverId(approverId);
        app.setSubjectType(SubjectType.USER);
        app.setSubjectId(applicantId);
        app.setResourceId(5L);
        app.setAction(DbAction.QUERY);
        app.setEffect(GrantEffect.ALLOW);
        app.setStatus("PENDING");
        return app;
    }

    @Test
    void applyRejectsSelfApproval() {
        GrantApplicationServiceImpl svc = newSvc(
            mock(GrantApplicationRepository.class), mock(WorkflowService.class),
            mock(FlowTaskQueryMapper.class), mock(GrantApprovalCallbackService.class));
        try (MockedStatic<LoginHelper> ms = mockStatic(LoginHelper.class)) {
            ms.when(LoginHelper::getUserId).thenReturn(100L);
            ServiceException ex = assertThrows(ServiceException.class, () -> svc.apply(applyBo(100L)));
            assertTrue(ex.getMessage().contains("不能审批本人申请"), ex.getMessage());
        }
    }

    @Test
    void approveRejectsApplicantSelfApproval() {
        GrantApplicationRepository repo = mock(GrantApplicationRepository.class);
        org.mockito.Mockito.when(repo.findById(1L)).thenReturn(pendingApp(100L, 200L));
        GrantApplicationServiceImpl svc = newSvc(repo, mock(WorkflowService.class),
            mock(FlowTaskQueryMapper.class), mock(GrantApprovalCallbackService.class));
        GrantApproveBo bo = new GrantApproveBo();
        bo.setApplicationId(1L);
        try (MockedStatic<LoginHelper> ms = mockStatic(LoginHelper.class)) {
            ms.when(LoginHelper::getUserId).thenReturn(100L); // 申请人
            ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(bo));
            assertTrue(ex.getMessage().contains("不能审批本人申请"), ex.getMessage());
        }
    }

    @Test
    void approveRejectsNonDesignatedApprover() {
        GrantApplicationRepository repo = mock(GrantApplicationRepository.class);
        org.mockito.Mockito.when(repo.findById(1L)).thenReturn(pendingApp(100L, 200L));
        GrantApplicationServiceImpl svc = newSvc(repo, mock(WorkflowService.class),
            mock(FlowTaskQueryMapper.class), mock(GrantApprovalCallbackService.class));
        GrantApproveBo bo = new GrantApproveBo();
        bo.setApplicationId(1L);
        try (MockedStatic<LoginHelper> ms = mockStatic(LoginHelper.class)) {
            ms.when(LoginHelper::getUserId).thenReturn(300L); // 非指定审批人
            ServiceException ex = assertThrows(ServiceException.class, () -> svc.approve(bo));
            assertTrue(ex.getMessage().contains("非指定审批人"), ex.getMessage());
        }
    }
}
