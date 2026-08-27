package org.dromara.db.workflow.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.dto.CompleteTaskDTO;
import org.dromara.common.core.domain.dto.StartProcessDTO;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.WorkflowService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.domain.GrantApplication;
import org.dromara.db.workflow.domain.bo.GrantApplyBo;
import org.dromara.db.workflow.domain.bo.GrantApproveBo;
import org.dromara.db.workflow.domain.vo.GrantApplicationVo;
import org.dromara.db.workflow.mapper.FlowTaskQueryMapper;
import org.dromara.db.workflow.repository.GrantApplicationRepository;
import org.dromara.db.workflow.service.GrantApplicationService;
import org.dromara.db.workflow.service.GrantApprovalCallbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询权限申请单服务实现（M2-02）。
 *
 * <p>apply：insert 申请单 → startCompleteTask（启动 + 办理申请人节点提交）→ 回填 flowInstanceId。
 * approve：completeTask 办理审批节点 → 同步触发 finish → ProcessEvent(FINISH) → 监听器 onApproval 生成 Grant。
 * reject/cancel：deleteInstance + 标记状态 + 主动 onRejection（不生成 Grant）。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantApplicationServiceImpl implements GrantApplicationService {

    private final GrantApplicationRepository applicationRepository;
    private final WorkflowService workflowService;
    private final FlowTaskQueryMapper flowTaskQueryMapper;
    private final GrantApprovalCallbackService callbackService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apply(GrantApplyBo bo) {
        Long applicantId = LoginHelper.getUserId();
        // 申请人不能审批本人申请（docs/03 §13 #9，服务端强制）
        if (bo.getApproverId().equals(applicantId)) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        // 校验截止时间晚于生效时间
        if (bo.getEffectiveAt() != null && bo.getExpiresAt() != null
            && bo.getExpiresAt().isBefore(bo.getEffectiveAt())) {
            throw new ServiceException("截止时间不能早于生效时间");
        }

        GrantApplication app = new GrantApplication();
        app.setTenantId(DbWorkflowConstants.TENANT_ID);
        app.setApplicantId(applicantId);
        app.setApproverId(bo.getApproverId());
        app.setSubjectType(bo.getSubjectType());
        app.setSubjectId(bo.getSubjectId());
        app.setResourceId(bo.getResourceId());
        app.setAction(bo.getAction());
        app.setEffect(bo.getEffect());
        app.setConditions(bo.getConditions());
        app.setEffectiveAt(bo.getEffectiveAt());
        app.setExpiresAt(bo.getExpiresAt());
        app.setReason(bo.getReason());
        app.setStatus(DbWorkflowConstants.STATUS_PENDING);
        app.setDelFlag("0");
        app.setCreateBy(applicantId);
        app.setCreateTime(Instant.now());
        Long id = applicationRepository.insert(app);

        // 启动审批流并办理申请人节点（提交）
        StartProcessDTO start = new StartProcessDTO();
        start.setBusinessId(String.valueOf(id));
        start.setFlowCode(DbWorkflowConstants.FLOW_CODE_QUERY_GRANT);
        Map<String, Object> vars = new HashMap<>();
        // 锁定审批节点办理人 = 目标审批人（WarmFlow assignment listener 消费）
        vars.put(DbWorkflowConstants.VAR_APPROVE_NODE, String.valueOf(bo.getApproverId()));
        start.setVariables(vars);
        workflowService.startCompleteTask(start);

        // 回填流程实例 ID
        Long flowInstanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(id));
        if (flowInstanceId != null) {
            applicationRepository.updateFlowInstanceId(id, flowInstanceId);
        } else {
            log.warn("申请单 {} 启动流程后未取到实例 ID", id);
        }
        return id;
    }

    @Override
    public void approve(GrantApproveBo bo) {
        GrantApplication app = requirePending(bo.getApplicationId());
        Long approverId = LoginHelper.getUserId();
        // 申请人不能自批（docs/03 §13 #9）
        if (approverId.equals(app.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        // 仅指定审批人可审批
        if (!approverId.equals(app.getApproverId())) {
            throw new ServiceException("非指定审批人，无权审批");
        }
        Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(app.getId()));
        if (instanceId == null) {
            throw new ServiceException("审批流程实例不存在");
        }
        Long taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_APPROVE);
        if (taskId == null) {
            throw new ServiceException("当前无待审批任务，可能已被办理或流程已结束");
        }
        // 办理审批节点（不忽略权限，WarmFlow 校验 handler 在 permissionList 内）
        CompleteTaskDTO dto = new CompleteTaskDTO();
        dto.setTaskId(taskId);
        dto.setMessage(bo.getMessage());
        dto.setHandler(String.valueOf(approverId));
        workflowService.completeTask(dto);
        // completeTask 同步触发 finish → ProcessEvent(FINISH) → GrantApprovalEventListener.onApproval
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(GrantApproveBo bo) {
        GrantApplication app = requirePending(bo.getApplicationId());
        Long approverId = LoginHelper.getUserId();
        if (approverId.equals(app.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        if (!approverId.equals(app.getApproverId())) {
            throw new ServiceException("非指定审批人，无权操作");
        }
        // 终止/删除流程实例（deleteInstance 不发布 ProcessEvent，故主动回调）
        workflowService.deleteInstance(List.of(String.valueOf(app.getId())));
        applicationRepository.updateStatus(app.getId(), DbWorkflowConstants.STATUS_REJECTED);
        callbackService.onRejection(app.getId(), approverId, bo.getMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(GrantApproveBo bo) {
        GrantApplication app = requirePending(bo.getApplicationId());
        Long userId = LoginHelper.getUserId();
        if (!userId.equals(app.getApplicantId())) {
            throw new ServiceException("仅申请人可撤销");
        }
        workflowService.deleteInstance(List.of(String.valueOf(app.getId())));
        applicationRepository.updateStatus(app.getId(), DbWorkflowConstants.STATUS_CANCELED);
        callbackService.onRejection(app.getId(), userId, "申请人撤销");
    }

    @Override
    public TableDataInfo<GrantApplicationVo> pageList(PageQuery pageQuery) {
        Long applicantId = LoginHelper.isSuperAdmin() ? null : LoginHelper.getUserId();
        var page = applicationRepository.page(applicantId, pageQuery);
        List<GrantApplicationVo> rows = new ArrayList<>();
        for (GrantApplication a : page.getRecords()) {
            rows.add(toVo(a));
        }
        return new TableDataInfo<>(rows, page.getTotal());
    }

    private GrantApplication requirePending(Long applicationId) {
        GrantApplication app = applicationRepository.findById(applicationId);
        if (app == null) {
            throw new ServiceException("申请单不存在");
        }
        if (!DbWorkflowConstants.STATUS_PENDING.equals(app.getStatus())) {
            throw new ServiceException("申请单已处理，当前状态：" + app.getStatus());
        }
        return app;
    }

    private static GrantApplicationVo toVo(GrantApplication a) {
        GrantApplicationVo vo = new GrantApplicationVo();
        vo.setId(a.getId());
        vo.setFlowInstanceId(a.getFlowInstanceId());
        vo.setApplicantId(a.getApplicantId());
        vo.setApproverId(a.getApproverId());
        vo.setSubjectType(a.getSubjectType());
        vo.setSubjectId(a.getSubjectId());
        vo.setResourceId(a.getResourceId());
        vo.setAction(a.getAction());
        vo.setEffect(a.getEffect());
        vo.setConditions(a.getConditions());
        vo.setEffectiveAt(a.getEffectiveAt());
        vo.setExpiresAt(a.getExpiresAt());
        vo.setReason(a.getReason());
        vo.setStatus(a.getStatus());
        vo.setGrantId(a.getGrantId());
        vo.setCreateTime(a.getCreateTime());
        return vo;
    }
}
