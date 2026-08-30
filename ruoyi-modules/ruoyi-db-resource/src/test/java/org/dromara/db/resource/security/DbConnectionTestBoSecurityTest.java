package org.dromara.db.resource.security;

import org.dromara.db.resource.domain.bo.DbConnectionTestBo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RES-004：临时连接测试密码不得在请求对象中长期驻留。
 *
 * @author DataGate
 */
@Tag("unit")
class DbConnectionTestBoSecurityTest {

    @Test
    void clearPasswordMustOverwriteCallerArrayAndRemoveReference() {
        char[] plaintext = "temporary-password".toCharArray();
        DbConnectionTestBo request = new DbConnectionTestBo();
        request.setPassword(plaintext);

        request.clearPassword();

        assertArrayEquals(new char[plaintext.length], plaintext);
        assertNull(request.getPassword());
    }
}
