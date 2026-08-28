package org.dromara.db.observability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.domain.DbSlowGovernanceLog;
import org.dromara.db.observability.domain.DbSlowSample;
import org.dromara.db.observability.governance.GovernanceStateMachine;
import org.dromara.db.observability.mapper.DbSlowFingerprintMapper;
import org.dromara.db.observability.mapper.DbSlowGovernanceLogMapper;
import org.dromara.db.observability.mapper.DbSlowSampleMapper;
import org.dromara.db.observability.service.ISlowQueryGovernanceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 慢查询治理实现（docs/07 §10）。
 *
 * 状态迁移经 {@link GovernanceStateMachine} 校验 + 乐观锁（version 不匹配抛 WORKFLOW_STATE_CONFLICT）；
 * 所有状态/负责人/评论变化追加写 {@code dbg_slow_governance_log}（不可覆盖）；
 * 治理动作写平台审计（appendIsolated，不阻塞治理主流程）。
 *
 * @author DataGate
 */
@Service
public class SlowQueryGovernanceServiceImpl implements ISlowQueryGovernanceService {

    private final DbSlowFingerprintMapper fingerprintMapper;
    private final DbSlowGovernanceLogMapper logMapper;
    private final DbSlowSampleMapper sampleMapper;
    private final IAuditService auditService;
    private final TransactionTemplate txTemplate;

    public SlowQueryGovernanceServiceImpl(DbSlowFingerprintMapper fingerprintMapper,
                                          DbSlowGovernanceLogMapper logMapper,
                                          DbSlowSampleMapper sampleMapper,
                                          IAuditService auditService,
                                          PlatformTransactionManager transactionManager) {
        this.fingerprintMapper = fingerprintMapper;
        this.logMapper = logMapper;
        this.sampleMapper = sampleMapper;
        this.auditService = auditService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public DbSlowFingerprint claim(Long fingerprintId, Long assigneeId, Long operatorId) {
        DbSlowFingerprint fp = loadOrThrow(fingerprintId);
        if (!"DISCOVERED".equals(fp.getGovernanceStatus())) {
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT,
                "只有 DISCOVERED 状态可认领");
        }
        return doTransition(fp, "CLAIMED", assigneeId, null, operatorId);
    }

    @Override
    public DbSlowFingerprint transition(Long fingerprintId, String toStatus, Integer version,
                                         String comment, Long operatorId) {
        DbSlowFingerprint fp = loadOrThrow(fingerprintId);
        if (version != null && fp.getVersion() != null && !version.equals(fp.getVersion())) {
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "指纹状态版本已变化，请刷新");
        }
        if (!GovernanceStateMachine.canTransition(fp.getGovernanceStatus(), toStatus)) {
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT,
                "非法状态迁移: " + fp.getGovernanceStatus() + " → " + toStatus);
        }
        return doTransition(fp, toStatus, null, comment, operatorId);
    }

    @Override
    public void comment(Long fingerprintId, String text, Long operatorId) {
        DbSlowFingerprint fp = loadOrThrow(fingerprintId);
        appendLog(fp.getId(), "COMMENT", null, null, null, null, text, null, null, null, operatorId);
        auditGov(fp, "COMMENT", AuditResult.SUCCESS, Map.of());
    }

    @Override
    public DbSlowFingerprint assign(Long fingerprintId, Long assigneeId, Long operatorId) {
        DbSlowFingerprint fp = loadOrThrow(fingerprintId);
        Long oldAssignee = fp.getAssigneeId();
        DbSlowFingerprint update = new DbSlowFingerprint();
        update.setId(fp.getId());
        update.setAssigneeId(assigneeId);
        int rows = fingerprintMapper.updateById(update);
        if (rows <= 0) {
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "指派失败，指纹状态已变化");
        }
        appendLog(fp.getId(), "ASSIGN", fp.getGovernanceStatus(), fp.getGovernanceStatus(),
            oldAssignee, assigneeId, null, null, null, null, operatorId);
        auditGov(fp, "ASSIGN", AuditResult.SUCCESS, Map.of("newAssignee", String.valueOf(assigneeId)));
        DbSlowFingerprint fresh = fingerprintMapper.selectById(fp.getId());
        return fresh != null ? fresh : fp;
    }

    @Override
    public List<DbSlowFingerprint> listFingerprints(String governanceStatus, Long dataSourceId, int limit) {
        LambdaQueryWrapper<DbSlowFingerprint> w = new LambdaQueryWrapper<>();
        if (governanceStatus != null && !governanceStatus.isBlank()) {
            w.eq(DbSlowFingerprint::getGovernanceStatus, governanceStatus);
        }
        if (dataSourceId != null) {
            w.eq(DbSlowFingerprint::getDataSourceId, dataSourceId);
        }
        w.orderByDesc(DbSlowFingerprint::getLastSeenAt).last("limit " + Math.max(1, Math.min(limit, 100)));
        return fingerprintMapper.selectList(w);
    }

    @Override
    public GovernanceDetail getDetail(Long fingerprintId, int sampleLimit) {
        DbSlowFingerprint fp = fingerprintMapper.selectById(fingerprintId);
        if (fp == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE);
        }
        List<DbSlowSample> samples = sampleMapper.selectList(new LambdaQueryWrapper<DbSlowSample>()
            .eq(DbSlowSample::getFingerprintId, fingerprintId)
            .orderByDesc(DbSlowSample::getOccurredAt)
            .last("limit " + Math.max(1, Math.min(sampleLimit, 50))));
        List<DbSlowGovernanceLog> logs = logMapper.selectList(new LambdaQueryWrapper<DbSlowGovernanceLog>()
            .eq(DbSlowGovernanceLog::getFingerprintId, fingerprintId)
            .orderByDesc(DbSlowGovernanceLog::getCreateTime)
            .last("limit 50"));
        return new GovernanceDetail(fp, samples, logs);
    }

    private DbSlowFingerprint doTransition(DbSlowFingerprint fp, String toStatus, Long newAssignee,
                                            String comment, Long operatorId) {
        String from = fp.getGovernanceStatus();
        Long oldAssignee = fp.getAssigneeId();
        DbSlowFingerprint update = new DbSlowFingerprint();
        update.setId(fp.getId());
        update.setGovernanceStatus(toStatus);
        if (newAssignee != null) {
            update.setAssigneeId(newAssignee);
        }
        // 乐观锁：fp 持有加载时的 version，updateById 校验
        update.setVersion(fp.getVersion());
        final int[] rows = new int[1];
        txTemplate.executeWithoutResult(s -> {
            rows[0] = fingerprintMapper.updateById(update);
            if (rows[0] > 0) {
                appendLog(fp.getId(), "STATUS_CHANGE", from, toStatus,
                    oldAssignee, newAssignee, comment, null, null, null, operatorId);
            }
        });
        if (rows[0] <= 0) {
            throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "状态迁移失败，指纹版本已变化，请刷新");
        }
        auditGov(fp, "STATUS_CHANGE:" + from + "->" + toStatus, AuditResult.SUCCESS, Map.of());
        // 重新加载以返回刷新后的 version（@Version 自增在 DB，实体对象未更新）
        DbSlowFingerprint fresh = fingerprintMapper.selectById(fp.getId());
        return fresh != null ? fresh : fp;
    }

    private void appendLog(Long fingerprintId, String action, String fromStatus, String toStatus,
                            Long oldAssignee, Long newAssignee, String comment, Date dueAt,
                            String metrics, Long relatedChangeId, Long operatorId) {
        DbSlowGovernanceLog log = new DbSlowGovernanceLog();
        log.setFingerprintId(fingerprintId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOldAssigneeId(oldAssignee);
        log.setNewAssigneeId(newAssignee);
        log.setComment(comment);
        log.setDueAt(dueAt);
        log.setMetrics(metrics);
        log.setRelatedChangeId(relatedChangeId);
        log.setOperatorId(operatorId);
        log.setCreateTime(new Date());
        logMapper.insert(log);
    }

    private DbSlowFingerprint loadOrThrow(Long fingerprintId) {
        DbSlowFingerprint fp = fingerprintMapper.selectById(fingerprintId);
        if (fp == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE, "慢查询指纹不存在");
        }
        return fp;
    }

    private void auditGov(DbSlowFingerprint fp, String action, AuditResult result, Map<String, Object> detail) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.CONFIG, "SLOW_GOVERNANCE:" + action, null,
                Map.of(),
                "SLOW_FINGERPRINT", String.valueOf(fp.getId()),
                Map.of("dataSourceId", String.valueOf(fp.getDataSourceId()),
                    "fromStatus", String.valueOf(fp.getGovernanceStatus())),
                result, null, null, null, detail));
        } catch (Exception ignored) {
            // 审计写入故障不阻塞治理主流程
        }
    }
}
