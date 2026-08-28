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
import org.dromara.db.core.change.ChangeRiskAnalyzer;
import org.dromara.db.core.change.RedisChangeCommandValidator;
import org.dromara.db.core.domain.RedisChangeCommand;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.spi.ChangeExecutionGateway;
import org.dromara.db.core.spi.EncryptedObjectStore;
import org.dromara.db.core.spi.ResourcePathResolver;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.workflow.constant.DbWorkflowConstants;
import org.dromara.db.workflow.domain.DbChangeExecution;
import org.dromara.db.workflow.domain.DbChangeOrder;
import org.dromara.db.workflow.domain.bo.ChangeApplyBo;
import org.dromara.db.workflow.domain.bo.ChangeApproveBo;
import org.dromara.db.workflow.domain.bo.ChangeScheduleBo;
import org.dromara.db.workflow.domain.vo.DbChangeExecutionVo;
import org.dromara.db.workflow.domain.vo.DbChangeOrderVo;
import org.dromara.db.workflow.mapper.DbChangeExecutionMapper;
import org.dromara.db.workflow.mapper.DbChangeOrderMapper;
import org.dromara.db.workflow.mapper.FlowTaskQueryMapper;
import org.dromara.db.workflow.service.ChangeOrderService;
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
 * SQL 变更工单服务实现（docs/03 §10.3、docs/05 §2.8、docs/06 §13，M5-02c）。
 *
 * <p>create→precheck→submit（两级审批）→schedule（执行窗口）→execute（幂等逐语句，专用变更账号）。
 * SQL 改动回 DRAFT 清空审批结论。执行前重新解析+重新鉴权。
 *
 * @author DataGate
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeOrderServiceImpl implements ChangeOrderService {

    private final DbChangeOrderMapper changeOrderMapper;
    private final DbChangeExecutionMapper changeExecutionMapper;
    private final WorkflowService workflowService;
    private final FlowTaskQueryMapper flowTaskQueryMapper;
    private final IFlwTaskService flwTaskService;
    private final IAuditService auditService;
    private final IDbDataSourceService dataSourceService;
    private final org.dromara.db.resource.mapper.DbResourceMapper resourceMapper;
    private final ConnectorRegistry connectorRegistry;
    private final Optional<ResourcePathResolver> pathResolver;
    private final Optional<EncryptedObjectStore> objectStore;
    private final AuthorizationDecisionService decisionService;
    private final Optional<ChangeExecutionGateway> changeExecutionGateway;
    private final Optional<org.dromara.db.core.spi.FeatureGateService> featureGateService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(ChangeApplyBo bo) {
        Long applicantId = LoginHelper.getUserId();
        if (applicantId.equals(bo.getBizApproverId()) || applicantId.equals(bo.getDbaApproverId())) {
            throw new ServiceException("申请人不能审批本人申请（docs/03 §9）");
        }
        org.dromara.db.core.enums.FeatureGate chgGate = "REDIS".equalsIgnoreCase(bo.getChangeType())
            ? org.dromara.db.core.enums.FeatureGate.REDIS_WRITE
            : ("DDL".equals(bo.getChangeType()) ? org.dromara.db.core.enums.FeatureGate.CHANGE_DDL : org.dromara.db.core.enums.FeatureGate.CHANGE_DML);
        if (featureGateService.isPresent() && !featureGateService.get().isEnabled(chgGate, bo.getDataSourceId())) {
            throw new ServiceException(chgGate + " 功能未灰度开放（docs/09 §14.3）");
        }
        DbDataSource ds = requireActiveDs(bo.getDataSourceId());
        boolean isRedis = "REDIS".equalsIgnoreCase(bo.getChangeType());
        List<Long> resourceIds;
        String fingerprint;
        if (isRedis) {
            List<RedisChangeCommand> cmds = parseRedisCommands(bo.getStatement());
            RedisPrefixResolution pr = resolveRedisPrefixes(ds, cmds);
            RedisChangeCommandValidator.ValidationOutcome vo = RedisChangeCommandValidator.validateAll(cmds, pr.prefixes);
            if (!vo.valid()) {
                throw new ServiceException("Redis 命令校验失败：" + vo.error());
            }
            resourceIds = pr.resourceIds;
            fingerprint = sha256Hex(bo.getStatement());
        } else {
            ParsedStatement stmt = parseChangeStatement(bo.getDataSourceId(), bo.getStatement());
            resourceIds = resolveResources(ds, bo, stmt);
            fingerprint = stmt.fingerprint();
        }
        // 锁定 SQL 密文
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
        DbChangeOrder order = new DbChangeOrder();
        order.setTenantId(DbWorkflowConstants.TENANT_ID);
        order.setRequestNo("CHG" + UUID.randomUUID().toString().replace("-", ""));
        order.setApplicantId(applicantId);
        order.setDataSourceId(bo.getDataSourceId());
        order.setDatabaseName(bo.getDatabaseName());
        order.setSchemaName(bo.getSchemaName());
        order.setChangeType(bo.getChangeType());
        order.setStatementEncrypted(sqlObj.objectKey());
        order.setStatementHash(sha256Hex(bo.getStatement()));
        order.setFingerprint(fingerprint);
        order.setResourceSnapshot(resourceSnapshotJson(resourceIds, 0L, bo.getBizApproverId(), bo.getDbaApproverId()));
        order.setRollbackPlan(bo.getRollbackPlan());
        order.setImpactSummary(bo.getImpactSummary());
        order.setStatus("DRAFT");
        order.setDelFlag("0");
        order.setCreateBy(applicantId);
        order.setCreateTime(Date.from(Instant.now()));
        changeOrderMapper.insert(order);
        audit(order, applicantId, AuditResult.SUCCESS, "CHANGE_CREATE", null);
        return order.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void precheck(Long orderId) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"DRAFT".equals(order.getStatus()) && !"PRECHECK_FAILED".equals(order.getStatus())) {
            throw new ServiceException("当前状态不可预检查：" + order.getStatus());
        }
        order.setStatus("PRECHECKING");
        changeOrderMapper.updateById(order);
        try {
            List<ParsedStatement> parsed = parseStatements(order.getDataSourceId(), decryptSql(order));
            ChangeRiskAnalyzer.AnalysisResult ar = ChangeRiskAnalyzer.analyze(parsed);
            order.setPrecheckResult(precheckJson(ar.risks(), ar.severity()));
            order.setStatus("PRECHECKED");
        } catch (RuntimeException e) {
            order.setPrecheckResult(precheckJson(List.of("PARSE_FAILED"), ChangeRiskAnalyzer.SEVERITY_HIGH));
            order.setStatus("PRECHECK_FAILED");
        }
        changeOrderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long orderId) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"PRECHECKED".equals(order.getStatus())) {
            throw new ServiceException("仅 PRECHECKED 可提交审批，当前：" + order.getStatus());
        }
        StartProcessDTO start = new StartProcessDTO();
        start.setBusinessId(String.valueOf(order.getId()));
        start.setFlowCode(DbWorkflowConstants.FLOW_CODE_CHANGE_APPROVAL);
        Map<String, Object> vars = new HashMap<>();
        Long bizApprover = ExportApprovalCallbackServiceImpl.parseApprover(order.getResourceSnapshot(), "bizApproverId");
        Long dbaApprover = ExportApprovalCallbackServiceImpl.parseApprover(order.getResourceSnapshot(), "dbaApproverId");
        vars.put(DbWorkflowConstants.VAR_BIZ_APPROVE, String.valueOf(bizApprover));
        vars.put(DbWorkflowConstants.VAR_DBA_APPROVE_CHANGE, String.valueOf(dbaApprover));
        start.setVariables(vars);
        workflowService.startCompleteTask(start);
        Long instanceId = workflowService.getInstanceIdByBusinessId(String.valueOf(order.getId()));
        order.setWorkflowInstanceId(instanceId);
        order.setStatus("PENDING_APPROVAL");
        changeOrderMapper.updateById(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(ChangeApproveBo bo) {
        DbChangeOrder order = requirePending(bo.getOrderId());
        Long userId = LoginHelper.getUserId();
        if (userId.equals(order.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        Long instanceId = order.getWorkflowInstanceId();
        Long taskId;
        Long expectedApprover;
        Long bizTask = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_BIZ_APPROVE);
        if (bizTask != null) {
            taskId = bizTask;
            expectedApprover = ExportApprovalCallbackServiceImpl.parseApprover(order.getResourceSnapshot(), "bizApproverId");
        } else {
            Long dbaTask = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_DBA_APPROVE);
            if (dbaTask == null) {
                throw new ServiceException("当前无待审批任务");
            }
            taskId = dbaTask;
            expectedApprover = ExportApprovalCallbackServiceImpl.parseApprover(order.getResourceSnapshot(), "dbaApproverId");
        }
        if (expectedApprover == null || !userId.equals(expectedApprover)) {
            throw new ServiceException("非当前节点指定审批人，无权审批");
        }
        var dto = new org.dromara.common.core.domain.dto.CompleteTaskDTO();
        dto.setTaskId(taskId);
        dto.setMessage(bo.getMessage());
        dto.setHandler(String.valueOf(userId));
        workflowService.completeTask(dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(ChangeApproveBo bo) {
        DbChangeOrder order = requirePending(bo.getOrderId());
        Long userId = LoginHelper.getUserId();
        if (userId.equals(order.getApplicantId())) {
            throw new ServiceException("申请人不能审批本人申请");
        }
        terminate(order, bo.getMessage());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(ChangeApproveBo bo) {
        DbChangeOrder order = requirePending(bo.getOrderId());
        Long userId = LoginHelper.getUserId();
        if (!userId.equals(order.getApplicantId())) {
            throw new ServiceException("仅申请人可撤销");
        }
        order.setStatus("CANCELED");
        changeOrderMapper.updateById(order);
        terminate(order, "申请人撤销" + (bo.getMessage() == null ? "" : "：" + bo.getMessage()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void schedule(ChangeScheduleBo bo) {
        DbChangeOrder order = changeOrderMapper.selectById(bo.getOrderId());
        if (order == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"APPROVED".equals(order.getStatus())) {
            throw new ServiceException("仅 APPROVED 可设置执行窗口，当前：" + order.getStatus());
        }
        if (bo.getExecutionWindowEnd().before(bo.getExecutionWindowStart())) {
            throw new ServiceException("执行窗口结束不能早于开始");
        }
        order.setExecutionWindowStart(bo.getExecutionWindowStart());
        order.setExecutionWindowEnd(bo.getExecutionWindowEnd());
        order.setStatus("SCHEDULED");
        changeOrderMapper.updateById(order);
        audit(order, LoginHelper.getUserId(), AuditResult.SUCCESS, "CHANGE_SCHEDULED", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChangeResult execute(Long orderId) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"SCHEDULED".equals(order.getStatus()) && !"FAILED".equals(order.getStatus())) {
            throw new ServiceException("仅 SCHEDULED/FAILED 可执行，当前：" + order.getStatus());
        }
        Date now = new Date();
        if (order.getExecutionWindowStart() != null && now.before(order.getExecutionWindowStart())) {
            throw new ServiceException("未到执行窗口（docs/05 §4.5）");
        }
        if (changeExecutionGateway.isEmpty() || objectStore.isEmpty()) {
            throw new ServiceException("变更执行依赖未配置");
        }
        // 幂等：同 idempotencyKey 已有终态尝试则不重复执行（防并发重复执行变更，docs/08 §10）
        String idemKey = sha256Hex(LoginHelper.getUserId() + ":" + orderId + ":" + order.getStatementHash());
        DbChangeExecution existing = changeExecutionMapper.selectOne(new LambdaQueryWrapper<DbChangeExecution>()
            .eq(DbChangeExecution::getIdempotencyKey, idemKey));
        if (existing != null && ("SUCCEEDED".equals(existing.getStatus()) || "FAILED".equals(existing.getStatus()))) {
            return new ChangeResult(existing.getId().toString(),
                ExecutionStatus.valueOf(existing.getStatus()),
                existing.getAffectedRows() == null ? 0 : existing.getAffectedRows(),
                existing.getStatementResults(), existing.getErrorCode(), 0);
        }
        // 执行前重新鉴权（CHANGE_DML/CHANGE_DDL，任一拒绝→失败，docs/06 §13）
        List<Long> resourceIds = ExportApprovalCallbackServiceImpl.parseResourceIds(order.getResourceSnapshot());
        boolean isRedisExec = "REDIS".equals(order.getChangeType());
        DbAction action = isRedisExec ? DbAction.REDIS_WRITE
            : ("DDL".equals(order.getChangeType()) ? DbAction.CHANGE_DDL : DbAction.CHANGE_DML);
        String decisionId = null;
        for (Long rid : resourceIds) {
            DecisionRequest dr = new DecisionRequest(order.getApplicantId(), null, null, rid, action, Map.of());
            AccessDecision d = decisionService.decide(dr);
            if (!d.allowed()) {
                return failExecution(order, idemKey, DbErrorCode.AUTH_RESOURCE_DENIED.name(), "执行前重鉴权拒绝：" + d.reasonCode(), 0);
            }
            if (decisionId == null) decisionId = d.decisionId();
        }
        if (decisionId == null) {
            decisionId = "change-" + UUID.randomUUID();
        }
        String sql;
        try (InputStream in = objectStore.get().read(order.getStatementEncrypted(), "v1").orElse(null)) {
            if (in == null) {
                return failExecution(order, idemKey, DbErrorCode.QUERY_PARSE_FAILED.name(), "锁定 SQL 解密失败", 0);
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return failExecution(order, idemKey, DbErrorCode.QUERY_PARSE_FAILED.name(), "锁定 SQL 解密异常", 0);
        }
        order.setStatus("RUNNING");
        changeOrderMapper.updateById(order);
        List<String> authorizedPrefixes = isRedisExec ? resolveAuthorizedPrefixes(resourceIds) : java.util.List.of();
        List<RedisChangeCommand> redisCommands = isRedisExec ? parseRedisCommands(sql) : java.util.List.of();
        ChangeExecutionRequest req = new ChangeExecutionRequest(
            order.getId(), order.getApplicantId(), null, null, order.getDataSourceId(),
            order.getDatabaseName(), order.getSchemaName(), sql, resourceIds, decisionId, idemKey, 300L,
            authorizedPrefixes, redisCommands);
        ChangeResult result;
        try {
            result = changeExecutionGateway.get().execute(req);
        } catch (RuntimeException e) {
            log.warn("变更执行异常 orderId={}", orderId, e);
            result = ChangeResult.failed("change-" + UUID.randomUUID(), DbErrorCode.INTERNAL_ERROR.name(), 0);
        }
        persistExecution(order, idemKey, result);
        return result;
    }

    @Override
    public DbChangeOrderVo getById(Long orderId) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        return order == null ? null : toVo(order);
    }

    @Override
    public TableDataInfo<DbChangeOrderVo> pageList(PageQuery pageQuery) {
        Long applicantId = LoginHelper.isSuperAdmin() ? null : LoginHelper.getUserId();
        LambdaQueryWrapper<DbChangeOrder> qw = new LambdaQueryWrapper<DbChangeOrder>()
            .eq(applicantId != null, DbChangeOrder::getApplicantId, applicantId)
            .orderByDesc(DbChangeOrder::getCreateTime);
        Page<DbChangeOrder> page = changeOrderMapper.selectPage(pageQuery.build(), qw);
        List<DbChangeOrderVo> rows = new ArrayList<>();
        for (DbChangeOrder o : page.getRecords()) {
            rows.add(toVo(o));
        }
        return new TableDataInfo<>(rows, page.getTotal());
    }

    @Override
    public List<DbChangeExecutionVo> listExecutions(Long orderId) {
        List<DbChangeExecution> execs = changeExecutionMapper.selectList(new LambdaQueryWrapper<DbChangeExecution>()
            .eq(DbChangeExecution::getOrderId, orderId)
            .orderByAsc(DbChangeExecution::getAttemptNo));
        List<DbChangeExecutionVo> rows = new ArrayList<>();
        for (DbChangeExecution e : execs) {
            rows.add(toExecVo(e));
        }
        return rows;
    }

    // ====================== 内部 ======================

    private ChangeResult failExecution(DbChangeOrder order, String idemKey, String errorCode, String reason, long affected) {
        order.setStatus("FAILED");
        changeOrderMapper.updateById(order);
        DbChangeExecution exec = newExecution(order, idemKey);
        exec.setStatus("FAILED");
        exec.setAffectedRows(affected);
        exec.setErrorCode(errorCode);
        exec.setErrorSummary(reason);
        exec.setFinishedAt(new Date());
        changeExecutionMapper.insert(exec);
        audit(order, LoginHelper.getUserId(), AuditResult.FAILURE, "CHANGE_EXECUTE_FAILED", errorCode + ":" + reason);
        return ChangeResult.failed(exec.getId().toString(), errorCode, 0);
    }

    private void persistExecution(DbChangeOrder order, String idemKey, ChangeResult result) {
        DbChangeExecution exec = newExecution(order, idemKey);
        exec.setStatus(result.status().name());
        exec.setAffectedRows(result.affectedRows());
        exec.setErrorCode(result.errorCode());
        exec.setStatementResults(result.statementResults());
        exec.setFinishedAt(new Date());
        changeExecutionMapper.insert(exec);
        ExecutionStatus st = result.status();
        order.setStatus(st == ExecutionStatus.SUCCEEDED ? "SUCCEEDED" : "FAILED");
        changeOrderMapper.updateById(order);
        audit(order, LoginHelper.getUserId(),
            st == ExecutionStatus.SUCCEEDED ? AuditResult.SUCCESS : AuditResult.FAILURE,
            "CHANGE_EXECUTE", result.errorCode() == null ? "" : result.errorCode());
    }

    private DbChangeExecution newExecution(DbChangeOrder order, String idemKey) {
        DbChangeExecution exec = new DbChangeExecution();
        exec.setOrderId(order.getId());
        exec.setAttemptNo((int) (changeExecutionMapper.selectCount(new LambdaQueryWrapper<DbChangeExecution>()
            .eq(DbChangeExecution::getOrderId, order.getId())) + 1));
        exec.setStartedAt(new Date());
        exec.setIdempotencyKey(idemKey);
        return exec;
    }

    private DbChangeOrder requirePending(Long orderId) {
        DbChangeOrder order = changeOrderMapper.selectById(orderId);
        if (order == null) {
            throw new ServiceException("工单不存在");
        }
        if (!"PENDING_APPROVAL".equals(order.getStatus())) {
            throw new ServiceException("工单当前状态不可审批：" + order.getStatus());
        }
        return order;
    }

    private void terminate(DbChangeOrder order, String comment) {
        Long instanceId = order.getWorkflowInstanceId();
        if (instanceId == null) {
            return;
        }
        Long taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_BIZ_APPROVE);
        if (taskId == null) {
            taskId = flowTaskQueryMapper.selectTaskId(instanceId, DbWorkflowConstants.NODE_DBA_APPROVE);
        }
        if (taskId == null) {
            return;
        }
        FlowTerminationBo tbo = new FlowTerminationBo();
        tbo.setTaskId(taskId);
        tbo.setComment(comment);
        flwTaskService.terminationTask(tbo);
    }

    private ParsedStatement parseChangeStatement(Long dataSourceId, String statement) {
        List<ParsedStatement> parsed = parseStatements(dataSourceId, statement);
        if (parsed.isEmpty()) {
            throw new ServiceException("SQL 解析失败关闭");
        }
        ParsedStatement stmt = parsed.get(0);
        if (stmt.requiredAction() != DbAction.CHANGE_DML && stmt.requiredAction() != DbAction.CHANGE_DDL) {
            throw new ServiceException("仅 DML/DDL 可创建变更工单");
        }
        return stmt;
    }

    private List<ParsedStatement> parseStatements(Long dataSourceId, String statement) {
        DbDataSource ds = requireActiveDs(dataSourceId);
        DataSourceType type = DataSourceType.valueOf(ds.getType());
        DataSourceConnector connector = connectorRegistry.get(type)
            .orElseThrow(() -> new ServiceException("该类型连接器未注册"));
        return connector.queryParser().parse(statement);
    }

    private DbDataSource requireActiveDs(Long dataSourceId) {
        DbDataSource ds = dataSourceService.queryById(dataSourceId);
        if (ds == null || !DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            throw new ServiceException("数据源不存在或未启用");
        }
        return ds;
    }

    private List<Long> resolveResources(DbDataSource ds, ChangeApplyBo bo, ParsedStatement stmt) {
        String defaultDb = (bo.getDatabaseName() != null && !bo.getDatabaseName().isBlank())
            ? bo.getDatabaseName() : ds.getDefaultDatabase();
        List<String> paths = ExportJobServiceImpl.completeDefaultDatabase(
            stmt.resourcePaths(), defaultDb, DataSourceType.valueOf(ds.getType()), bo.getSchemaName());
        if (paths.isEmpty() || pathResolver.isEmpty()) {
            return List.of();
        }
        return pathResolver.get().resolve(ds.getId(), defaultDb, paths).stream()
            .filter(java.util.Objects::nonNull).toList();
    }

    private String decryptSql(DbChangeOrder order) {
        if (objectStore.isEmpty()) {
            throw new ServiceException("加密对象存储未配置");
        }
        try (InputStream in = objectStore.get().read(order.getStatementEncrypted(), "v1").orElse(null)) {
            if (in == null) {
                throw new ServiceException("锁定 SQL 解密失败");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServiceException("锁定 SQL 解密异常");
        }
    }

    /** Redis：解析命令 JSON（[{op,key,args}]）为结构化命令列表 */
    private List<RedisChangeCommand> parseRedisCommands(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = m.readTree(json);
            if (arr == null || !arr.isArray()) {
                throw new ServiceException("Redis 命令 JSON 格式错误");
            }
            List<RedisChangeCommand> cmds = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                String op = n.has("op") ? n.get("op").asText() : null;
                String key = n.has("key") ? n.get("key").asText() : null;
                List<String> args = new ArrayList<>();
                if (n.has("args") && n.get("args").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode a : n.get("args")) {
                        args.add(a.asText());
                    }
                }
                cmds.add(new RedisChangeCommand(op, key, args));
            }
            return cmds;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException("Redis 命令 JSON 解析失败");
        }
    }

    /** Redis：按命令 key 解析命中的 KEY_PREFIX_POLICY 资源（前缀+ID）。任一 key 无匹配前缀→越权拒绝。 */
    private RedisPrefixResolution resolveRedisPrefixes(DbDataSource ds, List<RedisChangeCommand> cmds) {
        List<DbResource> prefixes = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .eq(DbResource::getDataSourceId, ds.getId())
            .eq(DbResource::getResourceType, "KEY_PREFIX_POLICY")
            .eq(DbResource::getStatus, "ACTIVE"));
        List<Long> resourceIds = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        for (RedisChangeCommand c : cmds) {
            String hit = null;
            for (DbResource r : prefixes) {
                String p = r.getNormalizedName() == null ? "" : r.getNormalizedName();
                if (!p.isEmpty() && c.key() != null && c.key().startsWith(p)) {
                    hit = p;
                    if (!resourceIds.contains(r.getId())) {
                        resourceIds.add(r.getId());
                    }
                    break;
                }
            }
            if (hit == null) {
                throw new ServiceException("Redis 命令 key 越权：未命中任何授权前缀");
            }
            if (!matched.contains(hit)) {
                matched.add(hit);
            }
        }
        return new RedisPrefixResolution(resourceIds, matched);
    }

    /** Redis：由 resourceId 解析授权前缀（KEY_PREFIX_POLICY normalizedName） */
    private List<String> resolveAuthorizedPrefixes(List<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return List.of();
        }
        List<DbResource> rows = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .in(DbResource::getId, resourceIds)
            .eq(DbResource::getResourceType, "KEY_PREFIX_POLICY"));
        List<String> prefixes = new ArrayList<>();
        for (DbResource r : rows) {
            if (r.getNormalizedName() != null && !r.getNormalizedName().isBlank()) {
                prefixes.add(r.getNormalizedName());
            }
        }
        return prefixes;
    }

    private record RedisPrefixResolution(List<Long> resourceIds, List<String> prefixes) {
    }

    private void audit(DbChangeOrder order, Long actorId, AuditResult result, String action, String reason) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.CHANGE, action, actorId, Map.of(),
                "DATA_SOURCE", String.valueOf(order.getDataSourceId()), Map.of(),
                result, null, null, null,
                Map.of("orderId", String.valueOf(order.getId()), "status", order.getStatus(),
                    "reason", reason == null ? "" : reason)));
        } catch (Exception e) {
            log.warn("变更审计写入失败 orderId={}", order.getId(), e);
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

    private static String resourceSnapshotJson(List<Long> resourceIds, long policyVersion, Long biz, Long dba) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            var n = m.createObjectNode();
            n.put("policyVersion", policyVersion);
            n.put("bizApproverId", biz);
            n.put("dbaApproverId", dba);
            var arr = n.putArray("resourceIds");
            for (Long id : resourceIds) arr.add(id);
            return m.writeValueAsString(n);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String precheckJson(List<String> risks, String severity) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            var n = m.createObjectNode();
            n.put("severity", severity);
            var arr = n.putArray("risks");
            for (String r : risks) arr.add(r);
            return m.writeValueAsString(n);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static DbChangeOrderVo toVo(DbChangeOrder o) {
        DbChangeOrderVo vo = new DbChangeOrderVo();
        vo.setId(o.getId());
        vo.setRequestNo(o.getRequestNo());
        vo.setApplicantId(o.getApplicantId());
        vo.setDataSourceId(o.getDataSourceId());
        vo.setDatabaseName(o.getDatabaseName());
        vo.setSchemaName(o.getSchemaName());
        vo.setChangeType(o.getChangeType());
        vo.setFingerprint(o.getFingerprint());
        vo.setResourceSnapshot(o.getResourceSnapshot());
        vo.setPrecheckResult(o.getPrecheckResult());
        vo.setRollbackPlan(o.getRollbackPlan());
        vo.setImpactSummary(o.getImpactSummary());
        vo.setExecutionWindowStart(o.getExecutionWindowStart());
        vo.setExecutionWindowEnd(o.getExecutionWindowEnd());
        vo.setWorkflowInstanceId(o.getWorkflowInstanceId());
        vo.setStatus(o.getStatus());
        vo.setCreateTime(o.getCreateTime());
        vo.setUpdateTime(o.getUpdateTime());
        return vo;
    }

    private static DbChangeExecutionVo toExecVo(DbChangeExecution e) {
        DbChangeExecutionVo vo = new DbChangeExecutionVo();
        vo.setId(e.getId());
        vo.setOrderId(e.getOrderId());
        vo.setAttemptNo(e.getAttemptNo());
        vo.setExecutionNode(e.getExecutionNode());
        vo.setCredentialId(e.getCredentialId());
        vo.setStartedAt(e.getStartedAt());
        vo.setFinishedAt(e.getFinishedAt());
        vo.setStatus(e.getStatus());
        vo.setAffectedRows(e.getAffectedRows());
        vo.setErrorCode(e.getErrorCode());
        vo.setErrorSummary(e.getErrorSummary());
        vo.setStatementResults(e.getStatementResults());
        return vo;
    }
}
