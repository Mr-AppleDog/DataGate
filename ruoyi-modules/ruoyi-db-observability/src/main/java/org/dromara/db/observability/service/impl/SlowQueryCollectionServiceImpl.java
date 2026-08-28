package org.dromara.db.observability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.MetricEventPublisher;
import org.dromara.db.core.spi.SlowQueryProvider;
import org.dromara.db.core.spi.SlowQueryProvider.SlowQueryPage;
import org.dromara.db.observability.domain.DbSlowCursor;
import org.dromara.db.observability.domain.DbSlowSource;
import org.dromara.db.observability.mapper.DbSlowCursorMapper;
import org.dromara.db.observability.mapper.DbSlowSourceMapper;
import org.dromara.db.observability.service.ISlowQueryCollectionService;
import org.dromara.db.observability.service.ISlowQueryIngestService;
import org.dromara.db.observability.service.ISlowQueryIngestService.IngestResult;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.mapper.DbDataSourceMapper;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 慢查询采集编排实现（docs/07 §4.1）。
 *
 * 流程：单源分布式锁 → 加载来源/数据源/连接器/监控凭据 → 构建 ConnectionProfile
 * → 加载游标 → provider.pull(profile, secret, cursor, limit) → ingestService.ingest →
 * 更新来源状态（成功清零连续失败，失败累加并在 ≥3 时置 ERROR，供告警切片 C 触发 COLLECTOR）。
 *
 * 单源失败不阻塞其他源（docs/07 §13）；监控账号独立于查询/变更账号（§4.1）。
 *
 * @author DataGate
 */
@Service
public class SlowQueryCollectionServiceImpl implements ISlowQueryCollectionService {

    private static final String LOCK_PREFIX = "datagate:slow_collect:";
    private static final String PARTITION_DEFAULT = "default";
    private static final int DEFAULT_LIMIT = 500;
    private static final long LOCK_WAIT_SEC = 5;
    private static final long LOCK_LEASE_SEC = 120;
    private static final int COLLECTOR_ALERT_THRESHOLD = 3;

    private final DbSlowSourceMapper slowSourceMapper;
    private final DbSlowCursorMapper cursorMapper;
    private final DbDataSourceMapper dataSourceMapper;
    private final ConnectorRegistry connectorRegistry;
    private final ICredentialVaultService credentialVaultService;
    private final ISlowQueryIngestService ingestService;
    private final RedissonClient redissonClient;
    private final IAuditService auditService;
    private final ObjectProvider<MetricEventPublisher> publisherProvider;

    public SlowQueryCollectionServiceImpl(DbSlowSourceMapper slowSourceMapper,
                                          DbSlowCursorMapper cursorMapper,
                                          DbDataSourceMapper dataSourceMapper,
                                          ConnectorRegistry connectorRegistry,
                                          ICredentialVaultService credentialVaultService,
                                          ISlowQueryIngestService ingestService,
                                          RedissonClient redissonClient,
                                          IAuditService auditService,
                                          ObjectProvider<MetricEventPublisher> publisherProvider) {
        this.slowSourceMapper = slowSourceMapper;
        this.cursorMapper = cursorMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.connectorRegistry = connectorRegistry;
        this.credentialVaultService = credentialVaultService;
        this.ingestService = ingestService;
        this.redissonClient = redissonClient;
        this.auditService = auditService;
        this.publisherProvider = publisherProvider;
    }

    @Override
    public CollectResult collectOne(Long slowSourceId) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + slowSourceId);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SEC, LOCK_LEASE_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CollectResult.skipped("lock-interrupted");
        }
        if (!locked) {
            return CollectResult.skipped("already-running");
        }
        try {
            return doCollect(slowSourceId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public int collectAll() {
        List<DbSlowSource> sources = slowSourceMapper.selectList(new LambdaQueryWrapper<DbSlowSource>()
            .eq(DbSlowSource::getStatus, "ACTIVE"));
        int collected = 0;
        for (DbSlowSource s : sources) {
            try {
                CollectResult r = collectOne(s.getId());
                if ("OK".equals(r.status())) {
                    collected++;
                }
            } catch (Exception ignored) {
                // 单源失败仅隔离该源（docs/07 §13）
            }
        }
        return collected;
    }

    @Override
    public List<DbSlowSource> listCollectors() {
        return slowSourceMapper.selectList(new LambdaQueryWrapper<DbSlowSource>()
            .orderByDesc(DbSlowSource::getCreateTime));
    }

    private CollectResult doCollect(Long slowSourceId) {
        DbSlowSource source = slowSourceMapper.selectById(slowSourceId);
        if (source == null) {
            return CollectResult.skipped("source-not-found");
        }
        if (!"ACTIVE".equals(source.getStatus()) && !"ERROR".equals(source.getStatus())) {
            return CollectResult.skipped("status-" + source.getStatus());
        }

        DbDataSource ds = dataSourceMapper.selectById(source.getDataSourceId());
        if (ds == null) {
            return markFailure(source, DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED.name(), "数据源不存在");
        }

        DataSourceType type;
        try {
            type = DataSourceType.valueOf(ds.getType());
        } catch (Exception e) {
            return markFailure(source, DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED.name(),
                "未知数据源类型: " + ds.getType());
        }
        Optional<DataSourceConnector> connector = connectorRegistry.get(type);
        if (connector.isEmpty() || connector.get().slowQueryProvider().isEmpty()) {
            return markFailure(source, DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED.name(),
                "该类型连接器不支持慢查询采集");
        }
        SlowQueryProvider provider = connector.get().slowQueryProvider().get();

        Optional<DbCredential> credential = credentialVaultService
            .findActive(source.getDataSourceId(), CredentialPurpose.MONITOR)
            .or(() -> credentialVaultService.findActive(source.getDataSourceId(), CredentialPurpose.QUERY));
        if (credential.isEmpty()) {
            return markFailure(source, DbErrorCode.CREDENTIAL_INVALID.name(),
                "未配置 MONITOR 或 QUERY 凭据");
        }

        ConnectionProfile profile = new ConnectionProfile(
            ds.getHost(), ds.getPort(), ds.getDefaultDatabase(),
            credential.get().getUsername(), null, parseTls(ds.getTlsMode()),
            Duration.ofSeconds(10), Duration.ofSeconds(30));

        DbSlowCursor cursorEntity = cursorMapper.selectOne(new LambdaQueryWrapper<DbSlowCursor>()
            .eq(DbSlowCursor::getSlowSourceId, slowSourceId)
            .eq(DbSlowCursor::getPartitionKey, PARTITION_DEFAULT));
        CollectorCursor cursor = cursorEntity == null ? null : new CollectorCursor(
            slowSourceId, PARTITION_DEFAULT, cursorEntity.getCursor(),
            cursorEntity.getLastRecordTime() == null ? null : cursorEntity.getLastRecordTime().toInstant(),
            cursorEntity.getVersion() == null ? 0L : cursorEntity.getVersion());

        try (SecretValue secret = credentialVaultService.resolveActiveSecret(credential.get().getId())) {
            SlowQueryPage page = provider.pull(profile, secret, cursor, DEFAULT_LIMIT);
            List<SlowQueryRecord> records = page.records() == null ? List.of() : page.records();
            IngestResult res = ingestService.ingest(slowSourceId, PARTITION_DEFAULT, records, page.nextCursor());
            markSuccess(source);
            auditCollect(source, AuditResult.SUCCESS,
                Map.of("accepted", res.accepted(), "duplicate", res.duplicate(),
                    "cursorConflict", res.cursorConflict()));
            return CollectResult.ok(res.accepted(), res.duplicate(),
                res.cursorUpdated(), res.cursorConflict());
        } catch (Exception e) {
            return markFailure(source, DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name(), safeSummary(e));
        }
    }

    private void markSuccess(DbSlowSource source) {
        DbSlowSource update = new DbSlowSource();
        update.setId(source.getId());
        update.setLastSuccessAt(new Date());
        update.setLagSeconds(0);
        update.setConsecutiveFailures(0);
        update.setStatus("ACTIVE");
        update.setLastErrorCode(null);
        update.setLastErrorSummary(null);
        update.setUpdateTime(new Date());
        slowSourceMapper.updateById(update);
    }

    private CollectResult markFailure(DbSlowSource source, String errorCode, String summary) {
        int failures = (source.getConsecutiveFailures() == null ? 0 : source.getConsecutiveFailures()) + 1;
        DbSlowSource update = new DbSlowSource();
        update.setId(source.getId());
        update.setConsecutiveFailures(failures);
        update.setLastErrorCode(errorCode);
        update.setLastErrorSummary(summary == null ? null : (summary.length() > 500 ? summary.substring(0, 500) : summary));
        update.setUpdateTime(new Date());
        if (failures >= COLLECTOR_ALERT_THRESHOLD) {
            update.setStatus("ERROR");
        }
        slowSourceMapper.updateById(update);
        auditCollect(source, AuditResult.FAILURE,
            Map.of("errorCode", errorCode, "failures", failures));
        publishCollectorHealth(source.getDataSourceId(), failures);
        return CollectResult.failed(errorCode, summary);
    }

    private void publishCollectorHealth(Long dataSourceId, int consecutiveFailures) {
        MetricEventPublisher pub = publisherProvider.getIfAvailable();
        if (pub == null) {
            return;
        }
        try {
            pub.publish(new SlowMetricEvent(dataSourceId, null, null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, 0, null, null, null, false, consecutiveFailures, true));
        } catch (Exception ignored) {
            // 评估失败不阻塞采集主流程（docs/07 §13）
        }
    }

    private void auditCollect(DbSlowSource source, AuditResult result, Map<String, Object> detail) {
        try {
            auditService.append(new AuditEventInput(
                AuditCategory.CONFIG, "SLOW_COLLECT", null,
                Map.of(),
                "DATA_SOURCE", String.valueOf(source.getDataSourceId()),
                Map.of("source", String.valueOf(source.getId()),
                    "type", String.valueOf(source.getCollectType())),
                result, null, null, null, detail));
        } catch (Exception ignored) {
            // 审计写入故障不阻塞采集主流程（采集落盘优先，docs/07 §13）
        }
    }

    private static TlsMode parseTls(String s) {
        if (s == null) return TlsMode.PREFER;
        try { return TlsMode.valueOf(s); } catch (Exception e) { return TlsMode.PREFER; }
    }

    private static String safeSummary(Exception e) {
        String s = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
