package org.dromara.db.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ResourceNode;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.domain.DbMetadataSyncJob;
import org.dromara.db.resource.domain.DbResource;
import org.dromara.db.resource.domain.vo.DbResourceVo;
import org.dromara.db.resource.mapper.DbDataSourceMapper;
import org.dromara.db.resource.mapper.DbMetadataSyncJobMapper;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IMetadataSyncService;
import org.dromara.db.resource.support.NetworkAddressValidator;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 元数据同步实现。
 *
 * <p>事务边界：任务记录（RUNNING/FAILED）独立提交，避免同步失败回滚掉任务本身；
 * 目录对齐（upsert + 标记下线）在单事务内完成，失败整体回滚保持一致性。</p>
 *
 * @author DataGate
 */
@Service
public class MetadataSyncServiceImpl implements IMetadataSyncService {

    private static final String SYNC_LOCK_PREFIX = "datagate:metadata-sync:";

    private final DbDataSourceMapper dataSourceMapper;
    private final DbResourceMapper resourceMapper;
    private final DbMetadataSyncJobMapper syncJobMapper;
    private final ConnectorRegistry connectorRegistry;
    private final ICredentialVaultService credentialVaultService;
    private final NetworkAddressValidator networkAddressValidator;
    private final IAuditService auditService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate txTemplate;

    public MetadataSyncServiceImpl(DbDataSourceMapper dataSourceMapper,
                                   DbResourceMapper resourceMapper,
                                   DbMetadataSyncJobMapper syncJobMapper,
                                   ConnectorRegistry connectorRegistry,
                                   ICredentialVaultService credentialVaultService,
                                   NetworkAddressValidator networkAddressValidator,
                                   IAuditService auditService,
                                   RedissonClient redissonClient,
                                   PlatformTransactionManager transactionManager) {
        this.dataSourceMapper = dataSourceMapper;
        this.resourceMapper = resourceMapper;
        this.syncJobMapper = syncJobMapper;
        this.connectorRegistry = connectorRegistry;
        this.credentialVaultService = credentialVaultService;
        this.networkAddressValidator = networkAddressValidator;
        this.auditService = auditService;
        this.redissonClient = redissonClient;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public DbMetadataSyncJob syncNow(Long dataSourceId) {
        RLock lock = redissonClient.getLock(SYNC_LOCK_PREFIX + dataSourceId);
        final boolean locked;
        try {
            // 不等待：前端应立即获知已有同步任务，而不是再次占用请求线程。
            // 无租约参数时由 Redisson watchdog 自动续期，适配大型目录的长时间扫描。
            locked = lock.tryLock();
        } catch (Exception e) {
            throw new DbServiceException(DbErrorCode.QUERY_ENGINE_UNAVAILABLE, "元数据同步协调服务不可用，请稍后重试");
        }
        if (!locked) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT, "该数据源正在同步元数据，请等待当前任务完成");
        }
        try {
            return syncUnderLock(dataSourceId);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception ignored) {
                // 同步结果已经持久化时不因释放锁链路的瞬态故障改写结果；watchdog 租约会自动过期。
            }
        }
    }

    private DbMetadataSyncJob syncUnderLock(Long dataSourceId) {
        DbDataSource ds = dataSourceMapper.selectById(dataSourceId);
        if (ds == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE);
        }
        if (!DataSourceStatus.VERIFYING.name().equals(ds.getStatus())
            && !DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            throw new DbServiceException(DbErrorCode.RESOURCE_STATE_CONFLICT,
                "数据源须先通过连接测试（VERIFYING/ACTIVE）才能同步元数据");
        }
        DataSourceType type = DataSourceType.valueOf(ds.getType());
        Optional<DataSourceConnector> connector = connectorRegistry.get(type);
        if (connector.isEmpty()) {
            throw new DbServiceException(DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED, "该类型连接器未注册");
        }
        Optional<DbCredential> credential = credentialVaultService.findActive(dataSourceId, CredentialPurpose.MONITOR)
            .or(() -> credentialVaultService.findActive(dataSourceId, CredentialPurpose.QUERY));
        if (credential.isEmpty()) {
            throw new DbServiceException(DbErrorCode.CREDENTIAL_INVALID, "请先配置 MONITOR 或 QUERY 凭据");
        }
        if (!networkAddressValidator.recheckResolved(ds.getHost())) {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.SECURITY, "METADATA_SYNC_DNS_RECHECK_FAILED", safeUserId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(dataSourceId), Map.of(),
                AuditResult.DENIED, null, null, null, null));
            throw new DbServiceException(DbErrorCode.RESOURCE_SSRF_BLOCKED, "主机解析结果不复核通过");
        }

        // 任务记录独立提交
        DbMetadataSyncJob job = new DbMetadataSyncJob();
        job.setDataSourceId(dataSourceId);
        job.setTriggerType("MANUAL");
        job.setStatus("RUNNING");
        job.setStartedAt(new Date());
        job.setFoundCount(0);
        job.setUpdatedCount(0);
        job.setDroppedCount(0);
        job.setCreateBy(safeUserId());
        job.setCreateTime(new Date());
        txTemplate.executeWithoutResult(s -> syncJobMapper.insert(job));

        try {
            ConnectionProfile profile = new ConnectionProfile(
                ds.getHost(), ds.getPort(), ds.getDefaultDatabase(), credential.get().getUsername(),
                null, TlsMode.valueOf(ds.getTlsMode()), null, null);
            List<ResourceNode> nodes;
            try (SecretValue secret = credentialVaultService.resolveActiveSecret(credential.get().getId())) {
                nodes = connector.get().metadataProvider().fetchCatalog(profile, secret);
            }

            // 目录对齐在单事务内完成
            txTemplate.executeWithoutResult(s -> alignCatalog(ds, job, nodes));

            auditService.append(new AuditEventInput(
                AuditCategory.CONFIG, "METADATA_SYNC", safeUserId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(dataSourceId),
                Map.of("name", String.valueOf(ds.getName()), "type", String.valueOf(ds.getType())),
                AuditResult.SUCCESS, null, null, null,
                Map.of("found", job.getFoundCount(), "updated", job.getUpdatedCount(),
                    "dropped", job.getDroppedCount(), "metadataVersion", job.getMetadataVersion())));
            return syncJobMapper.selectById(job.getId());
        } catch (Exception e) {
            String summary = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (summary.length() > 200) {
                summary = summary.substring(0, 200);
            }
            String errorSummary = summary;
            txTemplate.executeWithoutResult(s -> {
                DbMetadataSyncJob failed = new DbMetadataSyncJob();
                failed.setId(job.getId());
                failed.setStatus("FAILED");
                failed.setFinishedAt(new Date());
                failed.setErrorCode(DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name());
                failed.setErrorSummary(errorSummary);
                syncJobMapper.updateById(failed);
            });
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.CONFIG, "METADATA_SYNC", safeUserId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(dataSourceId), Map.of(),
                AuditResult.FAILURE, null, null, null,
                Map.of("errorCode", DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name())));
            throw new DbServiceException(DbErrorCode.QUERY_ENGINE_UNAVAILABLE, "元数据同步失败: " + errorSummary);
        }
    }

    /**
     * 目录对齐：新增/更新/下线。调用方保证在事务内。
     */
    private void alignCatalog(DbDataSource ds, DbMetadataSyncJob job, List<ResourceNode> nodes) {
        Long dataSourceId = ds.getId();
        Long maxVersion = resourceMapper.selectObjs(new LambdaQueryWrapper<DbResource>()
                .select(DbResource::getMetadataVersion)
                .eq(DbResource::getDataSourceId, dataSourceId)
                .orderByDesc(DbResource::getMetadataVersion)
                .last("limit 1"))
            .stream().findFirst().map(o -> ((Number) o).longValue()).orElse(0L);
        long version = maxVersion + 1;
        Date now = new Date();

        List<DbResource> existing = resourceMapper.selectList(new LambdaQueryWrapper<DbResource>()
            .eq(DbResource::getDataSourceId, dataSourceId));
        Map<String, DbResource> byPath = new HashMap<>();
        for (DbResource r : existing) {
            byPath.put(r.getCanonicalPath(), r);
        }
        // 路径 -> id（父节点解析用），先用存量播种
        Map<String, Long> idByPath = new HashMap<>();
        for (DbResource r : existing) {
            idByPath.put(r.getCanonicalPath(), r.getId());
        }

        Set<String> seenPaths = new HashSet<>();
        int found = 0;
        int updated = 0;
        for (ResourceNode node : distinctNodes(nodes)) {
            String path = node.canonicalPath();
            seenPaths.add(path);
            DbResource current = byPath.get(path);
            if (current == null) {
                DbResource insert = new DbResource();
                insert.setDataSourceId(dataSourceId);
                Long parentId = node.parentPath().isEmpty() ? 0L : idByPath.get(node.parentPath());
                insert.setParentId(parentId == null ? 0L : parentId);
                insert.setResourceType(node.type().name());
                insert.setPhysicalName(node.physicalName());
                insert.setNormalizedName(node.normalizedName());
                insert.setCanonicalPath(path);
                insert.setMetadata(JsonUtils.toJsonString(node.metadata()));
                insert.setStatus("ACTIVE");
                insert.setMetadataVersion(version);
                insert.setFirstSeenAt(now);
                insert.setLastSeenAt(now);
                insert.setCreateTime(now);
                resourceMapper.insert(insert);
                byPath.put(path, insert);
                idByPath.put(path, insert.getId());
                found++;
            } else {
                current.setLastSeenAt(now);
                current.setMetadata(JsonUtils.toJsonString(node.metadata()));
                current.setNormalizedName(node.normalizedName());
                current.setMetadataVersion(version);
                current.setUpdateTime(now);
                if (!"ACTIVE".equals(current.getStatus())) {
                    current.setStatus("ACTIVE");
                }
                resourceMapper.updateById(current);
                updated++;
            }
        }

        // 存量中本次未出现的 ACTIVE 资源标记 DROPPED（不删除，授权引用仍可审计）
        int dropped = 0;
        for (DbResource r : existing) {
            if ("ACTIVE".equals(r.getStatus()) && !seenPaths.contains(r.getCanonicalPath())) {
                DbResource mark = new DbResource();
                mark.setId(r.getId());
                mark.setStatus("DROPPED");
                mark.setMetadataVersion(version);
                mark.setUpdateTime(now);
                resourceMapper.updateById(mark);
                dropped++;
            }
        }

        DbMetadataSyncJob done = new DbMetadataSyncJob();
        done.setId(job.getId());
        done.setStatus("SUCCESS");
        done.setFinishedAt(new Date());
        done.setMetadataVersion(version);
        done.setFoundCount(found);
        done.setUpdatedCount(updated);
        done.setDroppedCount(dropped);
        syncJobMapper.updateById(done);

        job.setStatus("SUCCESS");
        job.setMetadataVersion(version);
        job.setFoundCount(found);
        job.setUpdatedCount(updated);
        job.setDroppedCount(dropped);
    }

    /**
     * 部分 MySQL 兼容引擎的 information_schema 会返回重复目录行。
     * 保留首次出现的节点，确保同一轮同步对规范路径只执行一次写入。
     */
    static List<ResourceNode> distinctNodes(List<ResourceNode> nodes) {
        Map<String, ResourceNode> byCanonicalPath = new LinkedHashMap<>();
        for (ResourceNode node : nodes) {
            String path = node.canonicalPath();
            if (path.length() <= 2000) {
                byCanonicalPath.putIfAbsent(path, node);
            }
        }
        return List.copyOf(byCanonicalPath.values());
    }

    @Override
    public List<DbMetadataSyncJob> recentJobs(Long dataSourceId, int limit) {
        return syncJobMapper.selectList(new LambdaQueryWrapper<DbMetadataSyncJob>()
            .eq(DbMetadataSyncJob::getDataSourceId, dataSourceId)
            .orderByDesc(DbMetadataSyncJob::getStartedAt)
            .last("limit " + Math.max(1, Math.min(limit, 50))));
    }

    @Override
    public List<DbResourceVo> listResources(Long dataSourceId, Long parentId) {
        return resourceMapper.selectVoList(new LambdaQueryWrapper<DbResource>()
            .eq(DbResource::getDataSourceId, dataSourceId)
            .eq(DbResource::getParentId, parentId == null ? 0L : parentId)
            .ne(DbResource::getStatus, "DROPPED")
            .orderByAsc(DbResource::getPhysicalName));
    }

    private Long safeUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String safeUsername() {
        try {
            String name = LoginHelper.getUsername();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }
}
