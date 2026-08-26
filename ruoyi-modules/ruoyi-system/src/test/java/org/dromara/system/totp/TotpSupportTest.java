package org.dromara.system.totp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOTP 算法测试（RFC 6238 测试向量 + Base32 + 恢复码）
 *
 * @author DataGate
 */
@Tag("unit")
class TotpSupportTest {

    /**
     * RFC 6238 附录 B：HMAC-SHA1，密钥 "12345678901234567890"（ASCII），
     * T=59s → 8 位码 94287082，6 位取低六位 287082。
     */
    @Test
    void rfc6238Vector() {
        byte[] secret = "12345678901234567890".getBytes();
        long step = 59L / 30L;
        assertEquals("287082", TotpSupport.codeAtStep(secret, step));
        // T=1111111109 → 07081804
        assertEquals("081804", TotpSupport.codeAtStep(secret, 1111111109L / 30L));
    }

    @Test
    void base32RoundTripCompatibleWithAuthenticator() {
        byte[] secret = "12345678901234567890".getBytes();
        // 已知 Base32：GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", TotpSupport.base32Encode(secret));
    }

    @Test
    void verifyAcceptsCurrentStepCode() {
        byte[] secret = TotpSupport.generateSecret();
        String code = TotpSupport.codeAtStep(secret, TotpSupport.currentStep());
        Long step = TotpSupport.verify(secret, code);
        assertNotNull(step);
        assertNull(TotpSupport.verify(secret, "000000x"));
        assertNull(TotpSupport.verify(secret, null));
        assertNull(TotpSupport.verify(secret, ""));
    }

    @Test
    void recoveryCodesAreUniqueAndHashedStably() {
        String[] codes = TotpSupport.generateRecoveryCodes();
        assertEquals(8, codes.length);
        long distinct = java.util.Arrays.stream(codes).distinct().count();
        assertEquals(8, distinct);
        String hash1 = TotpSupport.recoveryHash(codes[0]);
        String hash2 = TotpSupport.recoveryHash(codes[0].replace("-", "").toLowerCase());
        assertEquals(hash1, hash2);
        assertNotEquals(hash1, TotpSupport.recoveryHash(codes[1]));
        assertTrue(codes[0].matches("[A-Z2-9]{4}-[A-Z2-9]{4}"));
    }
}
