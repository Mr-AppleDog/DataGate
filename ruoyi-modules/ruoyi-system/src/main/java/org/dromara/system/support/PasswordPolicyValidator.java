package org.dromara.system.support;

import org.dromara.common.core.utils.StringUtils;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * 密码策略校验器（IAM-003）。
 *
 * <p>规则（policy_version = 1）：</p>
 * <ul>
 *   <li>长度 12–64 位（上限避免 BCrypt 72 字节截断造成语义歧义）；</li>
 *   <li>至少包含四类字符中的三类：小写字母、大写字母、数字、特殊字符；</li>
 *   <li>不得包含账号信息：用户名、手机号、邮箱及其本地部分（长度≥3 才参与匹配，不区分大小写）。</li>
 * </ul>
 *
 * <p>安全约束：抛出的异常与返回信息绝不包含密码原文。</p>
 *
 * @author DataGate
 */
public final class PasswordPolicyValidator {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 64;
    public static final int POLICY_VERSION = 1;

    private static final int MIN_TOKEN_LENGTH = 3;

    private PasswordPolicyValidator() {
    }

    /**
     * 校验密码，返回违规说明列表（空列表 = 通过）。信息不包含密码原文。
     *
     * @param rawPassword   待校验明文密码（仅内存短时存在，调用方负责不落盘）
     * @param accountTokens 账号信息（用户名/手机/邮箱等），可为 null 元素
     */
    public static List<String> violations(String rawPassword, String... accountTokens) {
        List<String> result = new ArrayList<>();
        if (rawPassword == null || rawPassword.isEmpty()) {
            result.add("密码不能为空");
            return result;
        }
        if (rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            result.add("密码长度须为 " + MIN_LENGTH + "-" + MAX_LENGTH + " 位");
        }
        int classes = 0;
        if (rawPassword.chars().anyMatch(c -> c >= 'a' && c <= 'z')) {
            classes++;
        }
        if (rawPassword.chars().anyMatch(c -> c >= 'A' && c <= 'Z')) {
            classes++;
        }
        if (rawPassword.chars().anyMatch(c -> c >= '0' && c <= '9')) {
            classes++;
        }
        if (rawPassword.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            classes++;
        }
        if (classes < 3) {
            result.add("密码须至少包含四类字符中的三类（小写/大写/数字/特殊字符）");
        }
        String lowered = rawPassword.toLowerCase();
        for (String token : accountTokens) {
            if (StringUtils.isBlank(token)) {
                continue;
            }
            String trimmed = token.trim();
            if (containsToken(lowered, trimmed)) {
                result.add("密码不得包含账号信息（用户名/手机号/邮箱）");
                break;
            }
            // 邮箱本地部分同样视为账号信息
            int at = trimmed.indexOf('@');
            if (at > 0 && containsToken(lowered, trimmed.substring(0, at))) {
                result.add("密码不得包含账号信息（用户名/手机号/邮箱）");
                break;
            }
        }
        return result;
    }

    /**
     * 校验不通过时抛出 {@link DbErrorCode#IAM_PASSWORD_POLICY_VIOLATION}。
     */
    public static void validate(String rawPassword, String... accountTokens) {
        List<String> violations = violations(rawPassword, accountTokens);
        if (!violations.isEmpty()) {
            throw new DbServiceException(DbErrorCode.IAM_PASSWORD_POLICY_VIOLATION,
                String.join("；", violations));
        }
    }

    private static boolean containsToken(String loweredPassword, String token) {
        return token.length() >= MIN_TOKEN_LENGTH && loweredPassword.contains(token.toLowerCase());
    }
}
