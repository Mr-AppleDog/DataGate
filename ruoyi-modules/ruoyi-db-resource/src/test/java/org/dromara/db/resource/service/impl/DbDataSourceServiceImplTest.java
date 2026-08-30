package org.dromara.db.resource.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.domain.vo.DbDataSourceVo;
import org.dromara.db.resource.mapper.DbDataSourceMapper;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IMetadataSyncService;
import org.dromara.db.resource.support.NetworkAddressValidator;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RES-002：查询控制台的数据源候选必须由服务端限定为 ACTIVE 状态。
 *
 * @author DataGate
 */
@Tag("unit")
class DbDataSourceServiceImplTest {

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void availableListMustFilterActiveDataSourcesOnServer() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "DbDataSourceServiceImplTest"),
            DbDataSource.class);
        DbDataSourceMapper mapper = mock(DbDataSourceMapper.class);
        DbDataSourceVo active = new DbDataSourceVo();
        active.setId(1L);
        active.setName("active-ds");
        active.setStatus(DataSourceStatus.ACTIVE.name());
        when(mapper.selectVoList(any(Wrapper.class))).thenReturn(List.of(active));

        DbDataSourceServiceImpl service = new DbDataSourceServiceImpl(
            mapper,
            mock(NetworkAddressValidator.class),
            mock(ConnectorRegistry.class),
            mock(ICredentialVaultService.class),
            mock(IAuditService.class),
            mock(IMetadataSyncService.class));

        List<DbDataSourceVo> result = service.queryAvailableList();

        assertEquals(List.of(active), result);
        ArgumentCaptor<LambdaQueryWrapper<DbDataSource>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectVoList(wrapperCaptor.capture());
        LambdaQueryWrapper<DbDataSource> wrapper = wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().toLowerCase().contains("status"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(DataSourceStatus.ACTIVE.name()));
    }

    @Test
    void firstEnableMustSyncMetadataBeforeBecomingActive() {
        DbDataSourceMapper mapper = mock(DbDataSourceMapper.class);
        IMetadataSyncService metadataSyncService = mock(IMetadataSyncService.class);
        IAuditService auditService = mock(IAuditService.class);
        DbDataSource verified = new DbDataSource();
        verified.setId(7L);
        verified.setStatus(DataSourceStatus.VERIFYING.name());
        when(mapper.selectById(7L)).thenReturn(verified);
        when(mapper.updateById(any(DbDataSource.class))).thenReturn(1);

        DbDataSourceServiceImpl service = new DbDataSourceServiceImpl(
            mapper,
            mock(NetworkAddressValidator.class),
            mock(ConnectorRegistry.class),
            mock(ICredentialVaultService.class),
            auditService,
            metadataSyncService);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(LoginHelper::getUsername).thenReturn("admin");
            assertTrue(service.enable(7L));
        }

        InOrder order = inOrder(mapper, metadataSyncService);
        order.verify(mapper).selectById(7L);
        order.verify(metadataSyncService).syncNow(7L);
        ArgumentCaptor<DbDataSource> updateCaptor = ArgumentCaptor.forClass(DbDataSource.class);
        order.verify(mapper).updateById(updateCaptor.capture());
        assertEquals(DataSourceStatus.ACTIVE.name(), updateCaptor.getValue().getStatus());
        verify(auditService).append(any());
    }
}
