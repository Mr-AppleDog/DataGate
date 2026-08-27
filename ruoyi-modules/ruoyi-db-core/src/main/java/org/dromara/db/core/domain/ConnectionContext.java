package org.dromara.db.core.domain;

import org.dromara.db.core.security.SecretValue;

/**
 * 执行上下文（docs/02 第 8 节执行链路 step 8-11）。
 *
 * <p>由编排器（db-executor）在真正执行前解析并组装：数据源→{@link ConnectionProfile}、
 * 凭据→{@link SecretValue}（解密、短时、用后销毁）、用户提交的原始可执行语句。
 * 与不可变的授权信封 {@link ExecutionPlan} 分离——ExecutionPlan 只承载“已授权什么”，
 * 本上下文承载“如何执行”，每次执行新建、用毕即弃。</p>
 *
 * <p>解决 ADR-008 记录的执行器契约缺口：ExecutionPlan 不含凭据（缺口 1）与可执行语句（缺口 2）。
 * 两缺口均经 ConnectionContext 参数流入执行器，不改冻结的 ExecutionPlan record。</p>
 *
 * @param profile          非秘密连接配置
 * @param secret           秘密（密码/Token），使用后由执行器销毁
 * @param originalStatement 用户提交的原始可执行语句（执行器须独立重新解析做纵深防御）
 * @author DataGate
 */
public record ConnectionContext(
    ConnectionProfile profile,
    SecretValue secret,
    String originalStatement
) {

    public ConnectionContext {
        if (profile == null) {
            throw new IllegalArgumentException("profile required");
        }
        if (secret == null) {
            throw new IllegalArgumentException("secret required");
        }
        if (originalStatement == null || originalStatement.isBlank()) {
            throw new IllegalArgumentException("originalStatement required");
        }
    }
}
