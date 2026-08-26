package org.dromara.system.support;

import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码策略测试（IAM-003：≥12 位、≥3 类字符、不含账号信息）
 *
 * @author DataGate
 */
@Tag("unit")
class PasswordPolicyValidatorTest {

    @Test
    void rejectsTooShort() {
        // 11 位，即使四类字符齐全也拒绝
        List<String> v = PasswordPolicyValidator.violations("aB1!cD2@eF3");
        assertTrue(v.stream().anyMatch(s -> s.contains("长度")));
    }

    @Test
    void acceptsMinLengthWithThreeClasses() {
        // 恰好 12 位：小写+大写+数字
        assertTrue(PasswordPolicyValidator.violations("abcdEFGH1234").isEmpty());
        // 小写+数字+特殊
        assertTrue(PasswordPolicyValidator.violations("abcdefgh123!").isEmpty());
    }

    @Test
    void rejectsTwoClassesOnly() {
        // 16 位但只有小写+数字
        List<String> v = PasswordPolicyValidator.violations("abcdefgh12345678");
        assertTrue(v.stream().anyMatch(s -> s.contains("三类")));
        // 纯小写
        assertTrue(PasswordPolicyValidator.violations("abcdefghijkl")
            .stream().anyMatch(s -> s.contains("三类")));
    }

    @Test
    void rejectsAccountInfo() {
        // 含用户名（不区分大小写）
        assertTrue(PasswordPolicyValidator.violations("Zhangsan#2026abc", "zhangsan")
            .stream().anyMatch(s -> s.contains("账号信息")));
        // 含手机号
        assertTrue(PasswordPolicyValidator.violations("Qz#13800138000xy", "u1", "13800138000", null)
            .stream().anyMatch(s -> s.contains("账号信息")));
        // 含邮箱本地部分
        assertTrue(PasswordPolicyValidator.violations("Qw!someone2026x", "u1", null, "someone@example.com")
            .stream().anyMatch(s -> s.contains("账号信息")));
        // 长度 <3 的账号信息不参与匹配（避免误杀）
        assertTrue(PasswordPolicyValidator.violations("abCDEF12!@xyz", "ab").isEmpty());
    }

    @Test
    void rejectsTooLong() {
        String long65 = "Aa1!" + "x".repeat(61);
        assertTrue(PasswordPolicyValidator.violations(long65).stream().anyMatch(s -> s.contains("长度")));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertTrue(PasswordPolicyValidator.violations(null).stream().anyMatch(s -> s.contains("不能为空")));
        assertTrue(PasswordPolicyValidator.violations("").stream().anyMatch(s -> s.contains("不能为空")));
    }

    @Test
    void validateThrowsStableErrorCodeWithoutPasswordEcho() {
        String weak = "weak";
        DbServiceException ex = assertThrows(DbServiceException.class,
            () -> PasswordPolicyValidator.validate(weak, "someuser"));
        assertEquals(DbErrorCode.IAM_PASSWORD_POLICY_VIOLATION.getCode(), ex.getErrorCode().getCode());
        // 异常信息绝不回显密码原文
        assertTrue(!ex.getMessage().contains(weak));
    }

    @Test
    void acceptsStrongPassword() {
        assertTrue(PasswordPolicyValidator.violations("R7!vQ2#mX9$kL4&z", "admin", "13800138000", "a@b.com").isEmpty());
    }
}
