package org.dromara.db.resource.security;

import org.dromara.db.resource.support.NetworkAddressValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 防护安全测试（docs/08 §7、docs/09 §10 M6-05）。
 *
 * <p>验证主机校验拒绝回环/链路本地/组播/未指定/云元数据/CGNAT 地址，
 * 防止数据源接入被用作 SSRF 探测内网/元数据。
 *
 * @author DataGate
 */
@Tag("unit")
class NetworkAddressSsrfSecurityTest {

    private final NetworkAddressValidator validator = new NetworkAddressValidator();

    @Test
    void loopback_denied() {
        assertFalse(validator.validate("127.0.0.1", 3306).allowed(), "回环地址不得接入");
    }

    @Test
    void any_local_denied() {
        assertFalse(validator.validate("0.0.0.0", 3306).allowed(), "未指定地址不得接入");
    }

    @Test
    void cloud_metadata_denied() {
        assertFalse(validator.validate("169.254.169.254", 80).allowed(), "云元数据地址不得接入（SSRF 经典目标）");
    }

    @Test
    void multicast_denied() {
        assertFalse(validator.validate("224.0.0.1", 3306).allowed(), "组播地址不得接入");
    }

    @Test
    void cgnat_denied() {
        assertFalse(validator.validate("100.64.0.1", 3306).allowed(), "CGNAT 地址段默认拒绝");
    }

    @Test
    void link_local_denied() {
        assertFalse(validator.validate("169.254.1.1", 3306).allowed(), "链路本地地址不得接入");
    }

    @Test
    void external_ip_allowed() {
        assertTrue(validator.validate("8.8.8.8", 53).allowed(), "外部合法 IP 应允许");
    }

    @Test
    void invalid_port_denied() {
        assertFalse(validator.validate("8.8.8.8", 0).allowed(), "端口 0 拒绝");
        assertFalse(validator.validate("8.8.8.8", 70000).allowed(), "端口超界拒绝");
    }

    @Test
    void null_host_denied() {
        assertFalse(validator.validate(null, 3306).allowed());
        assertFalse(validator.validate(" ", 3306).allowed());
    }

    @Test
    void recheck_loopback_false() {
        assertFalse(validator.recheckResolved("127.0.0.1"), "连接前复核回环应拒绝");
    }
}
