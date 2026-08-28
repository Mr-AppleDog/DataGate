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
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.AuthorizationDecisionService;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.ResourcePathResolver;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.domain.DbExportJob;
import org.dromara.db.workflow.domain.bo.ExportApplyBo;
import org.dromara.db.workflow.domain.bo.ExportApproveBo;
import org.dromara.db.workflow.domain.vo.DbExportJobVo;
import org.dromara.db.workflow.mapper.DbExportJobMapper;
import org.dromara.db.workflow.mapper.FlowTaskQueryMapper;
import org.dromara.db.workflow.service.ExportJobService;
import org.dromara.workflow.domain.bo.FlowTerminationBo;
import org.dromara.workflow.service.IFlwTaskService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 导出工单服务实现（docs/03 §10.2、docs/05 §2.7、docs/06 §12，M5-01c）。
 *
 * <p>apply：解析+鉴权+锁定SQL密文+启动两级审批流。approve：办理当前审批节点（Owner/DBA），
 * 申请人不能审批本人。一次性下载票据（5min，单次），对象 24h 生命周期（惰性到期）。
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportJobServiceImpl implements ExportJobService {

    private static final long TICKET_TTL_MS = 5 * 60_000L;
    private static final long OBJECT_TTL_MS = 24 * 3600_000L;

    private final DbExportJobMapper exportJobMapper;
    private final WorkflowService workflowService;
    private final FlowTaskQueryMapper flowTaskQueryMapper;
    private final IFlwTaskService flwTaskService;
    private final IAuditService auditService;
    private final IDbDataSourceService dataSourceService;
    private final ConnectorRegistry connectorRegistry;
    private final Optional<ResourcePathResolver> pathResolver;
    private final Optional<EncryptedObjectStore> objectStore;
    private final AuthorizationDecisionService decisionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apply(ExportApplyBo bo) {
        Long applicantId = LoginHelper.getUserId();
        if (applicantId.equals(bo.getOwnerApproverId()) || applicantId.equals(bo.getDbaApproverId())) {
            throw new ServiceException("申请人不能审批本人申请（docs/03 §9）");
        }
        // 解析数据源 + 连接器
        DbDataSource ds = dataSourceService.queryById(bo.getDataSourceId());
        if (ds == null || !DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            throw new ServiceException("数据源不存在或未启用");
        }
        DataSourceType type = DataSourceType.valueOf(ds.getType());
        DataSourceConnector connector = connectorRegistry.get(type)
            .orElseThrow(() -> new ServiceException("该类型连接器未注册"));
        // 解析 SQL（只读单语句，docs/06 §12）
        List<ParsedStatement> parsed;
        try {
            parsed = connector.queryParser().parse(bo.getStatement());
        } catch (RuntimeException e) {
            throw new ServiceException("SQL 解析失败关闭");
        }
        if (parsed.size() != 1 || !parsed.get(0).readonly()) {
            throw new ServiceException("导出只允许只读单语句");
        }
        ParsedStatement stmt = parsed.get(0);
        // 资源解析
        String defaultDb = (bo.getDatabaseName() != null && !bo.getDatabaseName().isBlank())
            ? bo.getDatabaseName() : ds.getDefaultDatabase();
        List<Long> resourceIds = resolveResources(bo.getDataSourceId(), defaultDb, bo.getSchemaName(), type, stmt);
        // 创建时鉴权 EXPORT（deny→拒绝创建）
        DecisionAgg agg = decideExport(applicantId, resourceIds);
        if (agg.denied) {
            throw new ServiceException("导出鉴权拒绝：" + agg.reason);
        }
        // 锁定 SQL 密文（复用加密对象存储）
        if (objectStore.isEmpty()) {
            throw new ServiceException("加密对象存储未配置，无法锁定 SQL");
        }
        byte[] sqlBytes = bo.getStatement().getBytes(StandardCharsets.UTF_8);
        org.dromara.db.core.domain.EncryptedObject sqlObj;
        try (InputStream in = new ByteArrayInputStream(sqlBytes)) {
            sqlObj = objectStore.get().create(in, sqlBytes.length);
        } catch (Exception e) {
            throw new ServiceException("锁定 SQL 加密失败");
        }
        // 组装工单
        DbExportJob job = new DbExportJob();
        job.setTenantId(DbWorkflowConstants.TENANT_ID);
        job.setRequestNo("EXP" + UUID.randomUUID().toString().replace("-", ""));
        job.setApplicantId(applicantId);
        job.setDataSourceId(bo.getDataSourceId());
        job.setDatabaseName(bo.getDatabaseName());
        job.setSchemaName(bo.getSchemaName());
        job.setStatementEncrypted(sqlObj.objectKey());
        job.setStatementHash(sha256Hex(bo.getStatement()));
        job.setFingerprint(stmt.fingerprint());
        job.setResourceSnapshot(resourceSnapshotJson(resourceIds, agg.policyVersion, bo.getOwnerApproverId(), bo.getDbaApproverId()));
        job.setLimits(limitsJson(bo.getMaxRows(), bo.getMaxBytes()));
        job.setDecisionId(agg.decisionId);
        job.setMaskingLevel(agg.masking.name());
        job.setStatus("DRAFT");
        job.setDownloadCount(0);
        job.setDelFlag("0");
        job.setCreateBy(applicantId);
        job.setCreateTime(Date.from(Instant.now()));
        exportJobMapper.insert(job);

        // 启动两级审批流并办理申请人节点
        StartProcessDTO start = new StartProcessDTO();
        start.setBusinessId(String.valueOf(job.getId()));
        start.setFlowCode(DbWorkflowConstants.FLOW_CODE_EXPORT_APPROVAL);
        Map<String, Object> vars = new HashMap<>();
        vars.put(DbWorkflowConstants.VAR_OWNER_APPROVE, String.valueOf(bo.getOwnerApproverId()));
        vars.put(DbWorkflowConstants.VAR_DBA_APPROVE, String.valueOf(bo.getDbaApproverId()));
        start.setVariables(vars);
        workflowService.startCompleteTask(start);
        Long flowInstanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(job.getId()));
        if (flowInstanceId != null) {
            job.setWorkflowInstanceId(flowInstanceId);
        }
        job.setStatus("PENDING_APPROVAL");
        exportJobMapper.updateById(job);
        audit(job, applicantId, AuditResult.SUCCESS, "EXPORT_APPLY", null);
        return job.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(ExportApproveBo bo) {
        DbExportJob job = requirePending(bo.getJobId());
        Long userId = LoginHelper.getUserId();
        if (userId.equals(job.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        Long instanceId = job.getWorkflowInstanceId();
        if (instanceId == null) {
            throw new ServiceException("审批流程实例不存在");
        }
        // 当前待办审批节点（owner_approve 或 dba_approve）
        Long ownerTask = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_OWNER_APPROVE);
        Long dbaTask = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_DBA_APPROVE);
        Long taskId;
        String node;
        Long expectedApprover;
        if (ownerTask != null) {
            taskId = ownerTask; node = DbWorkflowConstants.NODE_OWNER_APPROVE;
            expectedApprover = ExportApprovalCallbackServiceImpl.parseApprover(job.getResourceSnapshot(), "ownerApproverId");
        } else if (dbaTask != null) {
            taskId = dbaTask; node = DbWorkflowConstants.NODE_DBA_APPROVE;
            expectedApprover = ExportApprovalCallbackServiceImpl.parseApprover(job.getResourceSnapshot(), "dbaApproverId");
        } else {
            throw new ServiceException("当前无待审批任务");
        }
        if (expectedApprover == null || !userId.equals(expectedApprover)) {
            throw new ServiceException("非当前节点指定审批人，无权审批");
        }
        var dto = new org.dromara.common.core.domain.dto.CompleteTaskDTO();
        dto.setTaskId(taskId);
        dto.setMessage(bo.getMessage());
        dto.setHandler(String.valueOf(userId));
        workflowService.completeTask(dto); // 完成当前节点；DBA 审批完成→FINISH→监听器 onApproval
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(ExportApproveBo bo) {
        DbExportJob job = requirePending(bo.getJobId());
        Long userId = LoginHelper.getUserId();
        if (userId.equals(job.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        terminate(job, userId, bo.getMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(ExportApproveBo bo) {
        DbExportJob job = requirePending(bo.getJobId());
        Long userId = LoginHelper.getUserId();
        if (!userId.equals(job.getApplicantId())) {
            throw new ServiceException("仅申请人可撤销");
        }
        job.setStatus("CANCELED");
        exportJobMapper.updateById(job);
        terminate(job, userId, "申请人撤销" + (bo.getMessage() == null ? "" : "：" + bo.getMessage()));
    }

    @Override
    public DbExportJobVo getById(Long jobId) {
        DbExportJob job = exportJobMapper.selectById(jobId);
        if (job == null) {
            return null;
        }
        lazyExpire(job);
        return toVo(job);
    }

    @Override
    public TableDataInfo<DbExportJobVo> pageList(PageQuery pageQuery) {
        Long applicantId = LoginHelper.isSuperAdmin() ? null : LoginHelper.getUserId();
        LambdaQueryWrapper<DbExportJob> qw = new LambdaQueryWrapper<DbExportJob>()
            .eq(applicantId != null, DbExportJob::getApplicantId, applicantId)
            .orderByDesc(DbExportJob::getCreateTime);
        Page<DbExportJob> page = exportJobMapper.selectPage(pageQuery.build(), qw);
        List<DbExportJobVo> rows = new ArrayList<>();
        for (DbExportJob j : page.getRecords()) {
            lazyExpire(j);
            rows.add(toVo(j));
        }
        return new TableDataInfo<>(rows, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String issueDownloadTicket(Long jobId) {
        DbExportJob job = exportJobMapper.selectById(jobId);
        if (job == null || !"SUCCEEDED".equals(job.getStatus())) {
            throw new ServiceException("工单不存在或未完成导出");
        }
        Long userId = LoginHelper.getUserId();
        if (!userId.equals(job.getApplicantId()) && !LoginHelper.isSuperAdmin()) {
            throw new ServiceException("仅申请人可领取下载票据");
        }
        if (job.getExpiresAt() != null && job.getExpiresAt().before(new Date())) {
            throw new ServiceException("导出文件已过期");
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        job.setTicketHash(sha256Hex(ticket));
        job.setTicketExpiresAt(new Date(System.currentTimeMillis() + TICKET_TTL_MS));
        exportJobMapper.updateById(job);
        audit(job, userId, AuditResult.SUCCESS, "EXPORT_TICKET_ISSUED", null);
        return ticket;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InputStream openDownload(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new ServiceException("票据无效");
        }
        DbExportJob job = exportJobMapper.selectOne(new LambdaQueryWrapper<DbExportJob>()
            .eq(DbExportJob::getTicketHash, sha256Hex(ticket)));
        if (job == null) {
            throw new ServiceException("票据不存在或已使用");
        }
        if (job.getTicketExpiresAt() == null || job.getTicketExpiresAt().before(new Date())) {
            job.setTicketHash(null);
            exportJobMapper.updateById(job);
            throw new ServiceException("票据已过期");
        }
        if (!"SUCCEEDED".equals(job.getStatus()) || job.getObjectKey() == null) {
            throw new ServiceException("工单不可下载");
        }
        if (objectStore.isEmpty()) {
            throw new ServiceException("加密对象存储未配置");
        }
        Optional<InputStream> in = objectStore.get().read(job.getObjectKey(), job.getEncryptionKeyRef());
        if (in.isEmpty()) {
            throw new ServiceException("导出文件不可读（可能已删除或密钥不可用）");
        }
        // 一次性：计次 + 失效票据
        job.setDownloadCount(job.getDownloadCount() == null ? 1 : job.getDownloadCount() + 1);
        job.setTicketHash(null);
        job.setTicketExpiresAt(null);
        exportJobMapper.updateById(job);
        audit(job, LoginHelper.getUserId(), AuditResult.SUCCESS, "EXPORT_DOWNLOAD", "download#" + job.getDownloadCount());
        return in.get();
    }

    // ====================== 内部 ======================

    private DbExportJob requirePending(Long jobId) {
        DbExportJob job = exportJobMapper.selectById(jobId);
        if (job == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"PENDING_APPROVAL".equals(job.getStatus())) {
            throw new ServiceException("工单当前状态不可审批：" + job.getStatus());
        }
        return job;
    }

    private void terminate(DbExportJob job, Long userId, String comment) {
        Long instanceId = job.getWorkflowInstanceId();
        if (instanceId == null) {
            return;
        }
        Long taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_OWNER_APPROVE);
        if (taskId == null) {
            taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_DBA_APPROVE);
        }
        if (taskId == null) {
            return;
        }
        FlowTerminationBo tbo = new FlowTerminationBo();
        tbo.setTaskId(taskId);
        tbo.setComment(comment);
        flwTaskService.terminationTask(tbo); // → TERMINATION → 监听器 onRejection
    }

    private List<Long> resolveResources(Long dataSourceId, String defaultDb, String schema, DataSourceType type,
                                        ParsedStatement stmt) {
        List<String> paths = completeDefaultDatabase(stmt.resourcePaths(), defaultDb, type, schema);
        if (paths.isEmpty() || pathResolver.isEmpty()) {
            return List.of();
        }
        return pathResolver.get().resolve(dataSourceId, defaultDb, paths).stream()
            .filter(java.util.Objects::nonNull).toList();
    }

    private DecisionAgg decideExport(Long applicantId, List<Long> resourceIds) {
        String decisionId = null;
        MaskingLevel masking = MaskingLevel.UNMASKED;
        long policyVersion = 0L;
        for (Long rid : resourceIds) {
            DecisionRequest dr = new DecisionRequest(applicantId, null, null, rid, DbAction.EXPORT, Map.of());
            AccessDecision d = decisionService.decide(dr);
            if (!d.allowed()) {
                return new DecisionAgg(true, d.reasonCode(), null, MaskingLevel.MASKED, 0);
            }
            if (decisionId == null) decisionId = d.decisionId();
            if (d.limits() != null && d.limits().maskingLevel() != null) {
                masking = moreRestrictive(masking, d.limits().maskingLevel());
            }
            policyVersion = Math.max(policyVersion, d.policyVersion());
        }
        return new DecisionAgg(false, null, decisionId, masking, policyVersion);
    }

    /**
     * 补全未限定库名的资源路径（docs/06 §4，与查询网关同逻辑）。
     */
    static java.util.List<String> completeDefaultDatabase(java.util.List<String> paths, String defaultDatabase,
                                                            DataSourceType type, String defaultSchema) {
        if (paths == null || paths.isEmpty()) {
            return paths == null ? java.util.List.of() : paths;
        }
        return switch (type) {
            case POSTGRESQL -> {
                String schema = (defaultSchema != null && !defaultSchema.isBlank()) ? defaultSchema : "public";
                yield paths.stream().map(p -> {
                    if (p != null && p.startsWith("/table/") && !p.startsWith("/db/") && !p.startsWith("/schema/")) {
                        return "/schema/" + schema + p;
                    }
                    return p;
                }).toList();
            }
            case REDIS -> {
                String db = (defaultDatabase != null && !defaultDatabase.isBlank()) ? defaultDatabase : "0";
                yield paths.stream().map(p -> {
                    if (p != null && p.startsWith("/kpp/") && !p.startsWith("/rdb/")) {
                        return "/rdb/" + db + p;
                    }
                    return p;
                }).toList();
            }
            default -> {
                if (defaultDatabase == null || defaultDatabase.isBlank()) {
                    yield paths;
                }
                String prefix = "/table/";
                yield paths.stream().map(p -> {
                    if (p != null && p.startsWith(prefix) && !p.startsWith("/db/")) {
                        return "/db/" + defaultDatabase + p;
                    }
                    return p;
                }).toList();
            }
        };
    }

    private static MaskingLevel moreRestrictive(MaskingLevel a, MaskingLevel b) {
        if (a == MaskingLevel.HIDDEN || b == MaskingLevel.HIDDEN) return MaskingLevel.HIDDEN;
        if (a == MaskingLevel.MASKED || b == MaskingLevel.MASKED) return MaskingLevel.MASKED;
        return MaskingLevel.UNMASKED;
    }

    private void lazyExpire(DbExportJob job) {
        if ("SUCCEEDED".equals(job.getStatus()) && job.getExpiresAt() != null
            && job.getExpiresAt().before(new Date()) && objectStore.isPresent()) {
            try {
                objectStore.get().delete(job.getObjectKey());
            } catch (Exception ignored) {
            }
            job.setStatus("EXPIRED");
            exportJobMapper.updateById(job);
        }
    }

    private void audit(DbExportJob job, Long actorId, AuditResult result, String action, String reason) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.EXPORT, action, actorId, Map.of(),
                "DATA_SOURCE", String.valueOf(job.getDataSourceId()), Map.of(),
                result, null, null, null,
                Map.of("jobId", String.valueOf(job.getId()), "status", job.getStatus(),
                    "reason", reason == null ? "" : reason)));
        } catch (Exception e) {
            log.warn("导出审计写入失败 jobId={}", job.getId(), e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-err";
        }
    }

    private static String resourceSnapshotJson(List<Long> resourceIds, long policyVersion, Long owner, Long dba) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            var n = m.createObjectNode();
            n.put("policyVersion", policyVersion);
            n.put("ownerApproverId", owner);
            n.put("dbaApproverId", dba);
            var arr = n.putArray("resourceIds");
            for (Long id : resourceIds) arr.add(id);
            return m.writeValueAsString(n);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String limitsJson(Long maxRows, Long maxBytes) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            var n = m.createObjectNode();
            if (maxRows != null) n.put("maxRows", maxRows);
            if (maxBytes != null) n.put("maxBytes", maxBytes);
            return m.writeValueAsString(n);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static DbExportJobVo toVo(DbExportJob job) {
        DbExportJobVo vo = new DbExportJobVo();
        vo.setId(job.getId());
        vo.setRequestNo(job.getRequestNo());
        vo.setApplicantId(job.getApplicantId());
        vo.setDataSourceId(job.getDataSourceId());
        vo.setDatabaseName(job.getDatabaseName());
        vo.setSchemaName(job.getSchemaName());
        vo.setFingerprint(job.getFingerprint());
        vo.setResourceSnapshot(job.getResourceSnapshot());
        vo.setLimits(job.getLimits());
        vo.setMaskingLevel(job.getMaskingLevel());
        vo.setStatus(job.getStatus());
        vo.setRowCount(job.getRowCount());
        vo.setResultBytes(job.getResultBytes());
        vo.setDownloadCount(job.getDownloadCount());
        vo.setExpiresAt(job.getExpiresAt());
        vo.setDeletedAt(job.getDeletedAt());
        vo.setWorkflowInstanceId(job.getWorkflowInstanceId());
        vo.setCreateTime(job.getCreateTime());
        vo.setUpdateTime(job.getUpdateTime());
        return vo;
    }

    private record DecisionAgg(boolean denied, String reason, String decisionId, MaskingLevel masking, long policyVersion) {
    }
}
