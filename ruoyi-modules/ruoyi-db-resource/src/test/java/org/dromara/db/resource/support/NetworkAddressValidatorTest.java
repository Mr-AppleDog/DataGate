package org.dromara.db.resource.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 防护校验器安全语料测试（docs/08 第 7 节）。
 * 每次修改校验规则必须全量回归本测试。
 *
 * @author DataGate
 */
@Tag("unit")
class NetworkAddressValidatorTest {

    private final NetworkAddressValidator validator = new NetworkAddressValidator();

    @Test
    void shouldAllowPrivateLanAndPublicIp() {
        assertTrue(validator.validate("192.168.149.128", 3306).allowed());
        assertTrue(validator.validate("10.0.0.8", 5432).allowed());
    }

    @Test
    void shouldDenyLoopbackAndUnspecified() {
        assertFalse(validator.validate("127.0.0.1", 3306).allowed());
        assertFalse(validator.validate("127.0.0.2", 3306).allowed());
        assertFalse(validator.validate("0.0.0.0", 3306).allowed());
        assertFalse(validator.validate("::1", 3306).allowed());
    }

    @Test
    void shouldDenyCloudMetadataAddress() {
        assertFalse(validator.validate("169.254.169.254", 80).allowed());
        assertFalse(validator.validate("100.100.100.100", 80).allowed());
        assertFalse(validator.validate("169.254.0.1", 8080).allowed());
    }

    @Test
    void shouldDenyCgnatRange() {
        assertFalse(validator.validate("100.64.0.1", 3306).allowed());
        assertFalse(validator.validate("100.127.255.254", 3306).allowed());
        assertTrue(validator.validate("100.128.0.1", 3306).allowed());
    }

    @Test
    void shouldDenyInvalidPortAndBlankHost() {
        assertFalse(validator.validate("192.168.1.1", 0).allowed());
        assertFalse(validator.validate("192.168.1.1", 65536).allowed());
        assertFalse(validator.validate("", 3306).allowed());
        assertFalse(validator.validate(null, 3306).allowed());
    }

    @Test
    void shouldDenyIllegalHostname() {
        assertFalse(validator.validate("evil host.com", 3306).allowed());
        assertFalse(validator.validate("-bad.com", 3306).allowed());
    }

    @Test
    void localhostResolutionMustBeDenied() {
        // localhost 解析到回环地址，必须被拒绝（防内网穿透）
        assertFalse(validator.validate("localhost", 3306).allowed());
    }
}
