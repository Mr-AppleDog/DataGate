package org.dromara.db.workflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.dto.StartProcessDTO;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.WorkflowService;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.auth.service.GrantAdminService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.domain.DbEmergencyAccess;
import org.dromara.db.workflow.domain.bo.EmergencyApplyBo;
import org.dromara.db.workflow.domain.bo.EmergencyApproveBo;
import org.dromara.db.workflow.domain.vo.DbEmergencyAccessVo;
import org.dromara.db.workflow.mapper.DbEmergencyAccessMapper;
import org.dromara.db.workflow.mapper.FlowTaskQueryMapper;
import org.dromara.db.workflow.service.EmergencyAccessService;
import org.dromara.workflow.domain.bo.FlowTerminationBo;
import org.dromara.workflow.service.IFlwTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 紧急访问服务实现（docs/03 §10.4、docs/10 M5-04）。
 *
 * <p>双人审批（审批人1/2 须不同且均非申请人）+ 2h 临时授权 + 事件编号 + 自动到期（grant validUntil）+
 * 即时通知 + 事后 24h 复盘；不续期。撤销已激活授权即时失效（revokeGrant + policyVersion 广播）。
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyAccessServiceImpl implements EmergencyAccessService {

    private final DbEmergencyAccessMapper emergencyAccessMapper;
    private final WorkflowService workflowService;
    private final FlowTaskQueryMapper flowTaskQueryMapper;
    private final IFlwTaskService flwTaskService;
    private final IAuditService auditService;
    private final GrantAdminService grantAdminService;
    private final java.util.Optional<org.dromara.db.core.spi.FeatureGateService> featureGateService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apply(EmergencyApplyBo bo) {
        Long applicantId = LoginHelper.getUserId();
        if (bo.getApprover1Id().equals(bo.getApprover2Id())) {
            throw new ServiceException("两名审批人不能相同（docs/03 §10.4 双人审批）");
        }
        if (featureGateService.isPresent() && !featureGateService.get().isEnabled(org.dromara.db.core.enums.FeatureGate.EMERGENCY_ACCESS)) {
            throw new ServiceException("紧急访问功能未灰度开放（docs/09 §14.3）");
        }
        if (applicantId.equals(bo.getApprover1Id()) || applicantId.equals(bo.getApprover2Id())) {
            throw new ServiceException("申请人不能为审批人（docs/03 §9）");
        }
        DbEmergencyAccess access = new DbEmergencyAccess();
        access.setTenantId(DbWorkflowConstants.TENANT_ID);
        access.setRequestNo("EMG" + UUID.randomUUID().toString().replace("-", ""));
        access.setEventNo(bo.getEventNo());
        access.setApplicantId(applicantId);
        access.setApprover1Id(bo.getApprover1Id());
        access.setApprover2Id(bo.getApprover2Id());
        access.setTargetResourceId(bo.getTargetResourceId());
        access.setTargetAction(bo.getTargetAction());
        access.setReason(bo.getReason());
        access.setStatus("DRAFT");
        access.setDelFlag("0");
        access.setCreateBy(applicantId);
        access.setCreateTime(Date.from(Instant.now()));
        emergencyAccessMapper.insert(access);

        StartProcessDTO start = new StartProcessDTO();
        start.setBusinessId(String.valueOf(access.getId()));
        start.setFlowCode(DbWorkflowConstants.FLOW_CODE_EMERGENCY_APPROVAL);
        Map<String, Object> vars = new HashMap<>();
        vars.put(DbWorkflowConstants.VAR_APPROVE1, String.valueOf(bo.getApprover1Id()));
        vars.put(DbWorkflowConstants.VAR_APPROVE2, String.valueOf(bo.getApprover2Id()));
        start.setVariables(vars);
        workflowService.startCompleteTask(start);
        Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(access.getId()));
        access.setWorkflowInstanceId(instanceId);
        access.setStatus("PENDING_APPROVAL");
        emergencyAccessMapper.updateById(access);
        audit(access, applicantId, AuditResult.SUCCESS, "EMERGENCY_APPLY", "eventNo=" + bo.getEventNo());
        return access.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(EmergencyApproveBo bo) {
        DbEmergencyAccess access = requirePending(bo.getAccessId());
        Long userId = LoginHelper.getUserId();
        if (userId.equals(access.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        Long instanceId = access.getWorkflowInstanceId();
        Long taskId;
        Long expected;
        Long t1 = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_APPROVE1);
        if (t1 != null) {
            taskId = t1;
            expected = access.getApprover1Id();
        } else {
            Long t2 = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_APPROVE2);
            if (t2 == null) {
                throw new ServiceException("当前无待审批任务");
            }
            taskId = t2;
            expected = access.getApprover2Id();
        }
        if (expected == null || !userId.equals(expected)) {
            throw new ServiceException("非当前节点指定审批人，无权审批");
        }
        var dto = new org.dromara.common.core.domain.dto.CompleteTaskDTO();
        dto.setTaskId(taskId);
        dto.setMessage(bo.getMessage());
        dto.setHandler(String.valueOf(userId));
        workflowService.completeTask(dto); // approve2 完成→FINISH→监听器 onApproval
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(EmergencyApproveBo bo) {
        DbEmergencyAccess access = requirePending(bo.getAccessId());
        Long userId = LoginHelper.getUserId();
        if (userId.equals(access.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        terminate(access, bo.getMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(EmergencyApproveBo bo) {
        DbEmergencyAccess access = requirePending(bo.getAccessId());
        Long userId = LoginHelper.getUserId();
        if (!userId.equals(access.getApplicantId())) {
            throw new ServiceException("仅申请人可撤销");
        }
        access.setStatus("CANCELED");
        emergencyAccessMapper.updateById(access);
        terminate(access, "申请人撤销" + (bo.getMessage() == null ? "" : "：" + bo.getMessage()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(EmergencyApproveBo bo) {
        DbEmergencyAccess access = emergencyAccessMapper.selectById(bo.getAccessId());
        if (access == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"ACTIVE".equals(access.getStatus())) {
            throw new ServiceException("仅 ACTIVE 可撤销授权，当前：" + access.getStatus());
        }
        if (access.getGrantId() != null) {
            grantAdminService.revokeGrant(access.getGrantId(), LoginHelper.getUserId());
        }
        access.setStatus("REVOKED");
        emergencyAccessMapper.updateById(access);
        audit(access, LoginHelper.getUserId(), AuditResult.FAILURE, "EMERGENCY_REVOKED", "即时撤销");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void postMortem(EmergencyApproveBo bo) {
        DbEmergencyAccess access = emergencyAccessMapper.selectById(bo.getAccessId());
        if (access == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"ACTIVE".equals(access.getStatus()) && !"EXPIRED".equals(access.getStatus())
            && !"POST_MORTEM_PENDING".equals(access.getStatus()) && !"REVOKED".equals(access.getStatus())) {
            throw new ServiceException("当前状态不可复盘：" + access.getStatus());
        }
        if (bo.getPostMortemContent() == null || bo.getPostMortemContent().isBlank()) {
            throw new ServiceException("复盘内容不能为空（docs/03 §10.4 事后 24h 复盘）");
        }
        boolean overdue = access.getPostMortemDueAt() != null && new Date().after(access.getPostMortemDueAt());
        access.setPostMortemContent(bo.getPostMortemContent());
        access.setPostMortemAt(new Date());
        access.setStatus("POST_MORTEM_DONE");
        emergencyAccessMapper.updateById(access);
        audit(access, LoginHelper.getUserId(), AuditResult.SUCCESS, "EMERGENCY_POST_MORTEM",
            overdue ? "逾期复盘" : "按时复盘");
    }

    @Override
    public DbEmergencyAccessVo getById(Long accessId) {
        DbEmergencyAccess access = emergencyAccessMapper.selectById(accessId);
        if (access == null) {
            return null;
        }
        lazyExpire(access);
        return toVo(access);
    }

    @Override
    public TableDataInfo<DbEmergencyAccessVo> pageList(PageQuery pageQuery) {
        Long applicantId = LoginHelper.isSuperAdmin() ? null : LoginHelper.getUserId();
        LambdaQueryWrapper<DbEmergencyAccess> qw = new LambdaQueryWrapper<DbEmergencyAccess>()
            .eq(applicantId != null, DbEmergencyAccess::getApplicantId, applicantId)
            .orderByDesc(DbEmergencyAccess::getCreateTime);
        Page<DbEmergencyAccess> page = emergencyAccessMapper.selectPage(pageQuery.build(), qw);
        List<DbEmergencyAccessVo> rows = new ArrayList<>();
        for (DbEmergencyAccess a : page.getRecords()) {
            lazyExpire(a);
            rows.add(toVo(a));
        }
        return new TableDataInfo<>(rows, page.getTotal());
    }

    // ====================== 内部 ======================

    private DbEmergencyAccess requirePending(Long accessId) {
        DbEmergencyAccess access = emergencyAccessMapper.selectById(accessId);
        if (access == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"PENDING_APPROVAL".equals(access.getStatus())) {
            throw new ServiceException("工单当前状态不可审批：" + access.getStatus());
        }
        return access;
    }

    private void terminate(DbEmergencyAccess access, String comment) {
        Long instanceId = access.getWorkflowInstanceId();
        if (instanceId == null) {
            return;
        }
        Long taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_APPROVE1);
        if (taskId == null) {
            taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_APPROVE2);
        }
        if (taskId == null) {
            return;
        }
        FlowTerminationBo tbo = new FlowTerminationBo();
        tbo.setTaskId(taskId);
        tbo.setComment(comment);
        flwTaskService.terminationTask(tbo);
    }

    private void lazyExpire(DbEmergencyAccess access) {
        if ("ACTIVE".equals(access.getStatus()) && access.getValidUntil() != null
            && access.getValidUntil().before(new Date())) {
            access.setStatus("EXPIRED");
            emergencyAccessMapper.updateById(access);
            audit(access, null, AuditResult.SUCCESS, "EMERGENCY_EXPIRED", "自动到期");
        }
    }

    private void audit(DbEmergencyAccess access, Long actorId, AuditResult result, String action, String reason) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.AUTH, action, actorId, Map.of(),
                "RESOURCE", String.valueOf(access.getTargetResourceId()), Map.of(),
                result, null, null, null,
                Map.of("accessId", String.valueOf(access.getId()), "eventNo", access.getEventNo(),
                    "status", access.getStatus(), "reason", reason == null ? "" : reason)));
        } catch (Exception e) {
            log.warn("紧急访问审计写入失败 accessId={}", access.getId(), e);
        }
    }

    private static DbEmergencyAccessVo toVo(DbEmergencyAccess a) {
        DbEmergencyAccessVo vo = new DbEmergencyAccessVo();
        vo.setId(a.getId());
        vo.setRequestNo(a.getRequestNo());
        vo.setEventNo(a.getEventNo());
        vo.setApplicantId(a.getApplicantId());
        vo.setApprover1Id(a.getApprover1Id());
        vo.setApprover2Id(a.getApprover2Id());
        vo.setTargetResourceId(a.getTargetResourceId());
        vo.setTargetAction(a.getTargetAction());
        vo.setReason(a.getReason());
        vo.setValidFrom(a.getValidFrom());
        vo.setValidUntil(a.getValidUntil());
        vo.setGrantId(a.getGrantId());
        vo.setStatus(a.getStatus());
        vo.setPostMortemDueAt(a.getPostMortemDueAt());
        vo.setPostMortemContent(a.getPostMortemContent());
        vo.setPostMortemAt(a.getPostMortemAt());
        vo.setCreateTime(a.getCreateTime());
        vo.setUpdateTime(a.getUpdateTime());
        return vo;
    }
}
