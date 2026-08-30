package org.dromara.db.resource.service;

import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.resource.domain.bo.DbConnectionTestBo;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.support.NetworkAddressValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RES-004 临时连接测试：命中允许网段、调用连接器、记录审计并清除临时密码。
 *
 * @author DataGate
 */
@Tag("unit")
class DataSourceConnectionTestServiceTest {

    @Test
    void temporaryCredentialMustBeClearedAfterSuccessfulTest() {
        ConnectorRegistry registry = mock(ConnectorRegistry.class);
        DataSourceConnector connector = mock(DataSourceConnector.class);
        IAuditService auditService = mock(IAuditService.class);
        when(registry.get(DataSourceType.MYSQL)).thenReturn(Optional.of(connector));
        when(connector.test(any(), any())).thenReturn(
            ConnectionTestResult.ok("8.4", Set.of(), Duration.ofMillis(3)));

        DataSourceConnectionTestService service = new DataSourceConnectionTestService(
            new NetworkAddressValidator("100.64.0.0/10"), registry, auditService);
        char[] plaintext = "temporary-password".toCharArray();
        DbConnectionTestBo request = new DbConnectionTestBo();
        request.setType("MYSQL");
        request.setHost("100.113.245.88");
        request.setPort(3306);
        request.setTlsMode("PREFER");
        request.setUsername("reader");
        request.setPassword(plaintext);

        ConnectionTestResult result = service.test(request);

        assertTrue(result.success());
        assertArrayEquals(new char[plaintext.length], plaintext);
        assertNull(request.getPassword());
        verify(connector).test(any(), any());
        verify(auditService).append(any());
    }
}
