package org.dromara.db.resource.service.impl;

import org.dromara.db.core.domain.ResourceNode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.enums.ResourceType;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.resource.mapper.DbDataSourceMapper;
import org.dromara.db.resource.mapper.DbMetadataSyncJobMapper;
import org.dromara.db.resource.mapper.DbResourceMapper;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.support.NetworkAddressValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RES-005：元数据目录同步必须对连接器返回的重复规范路径保持幂等。
 *
 * @author DataGate
 */
@Tag("unit")
class MetadataSyncServiceImplTest {

    @Test
    void duplicateCanonicalPathsMustBeInsertedOnlyOnce() {
        ResourceNode first = new ResourceNode(ResourceType.DATABASE, "", "doc-loom", Map.of("charset", "utf8mb4"));
        ResourceNode duplicate = new ResourceNode(ResourceType.DATABASE, "", "doc-loom", Map.of("charset", "utf8mb4"));

        List<ResourceNode> result = MetadataSyncServiceImpl.distinctNodes(List.of(first, duplicate));

        assertEquals(1, result.size());
        assertEquals("/db/doc-loom", result.get(0).canonicalPath());
    }

    @Test
    void concurrentSyncMustBeRejectedBeforeReadingDataSource() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        DbDataSourceMapper dataSourceMapper = mock(DbDataSourceMapper.class);
        when(redissonClient.getLock("datagate:metadata-sync:7")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);

        MetadataSyncServiceImpl service = new MetadataSyncServiceImpl(
            dataSourceMapper,
            mock(DbResourceMapper.class),
            mock(DbMetadataSyncJobMapper.class),
            mock(ConnectorRegistry.class),
            mock(ICredentialVaultService.class),
            mock(NetworkAddressValidator.class),
            mock(IAuditService.class),
            redissonClient,
            mock(PlatformTransactionManager.class));

        DbServiceException error = assertThrows(DbServiceException.class, () -> service.syncNow(7L));

        assertEquals(DbErrorCode.RESOURCE_STATE_CONFLICT, error.getErrorCode());
        verify(dataSourceMapper, never()).selectById(7L);
    }
}
