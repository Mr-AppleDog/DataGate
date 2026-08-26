package org.dromara.db.core.authz;

import org.dromara.db.core.enums.MaskingLevel;

/**
 * 判定合并后的最终限制（docs/03 第 7.3、7.4 节）。
 *
 * <p>取“所选授权路径限制”与“环境硬上限”的较小值；
 * 拒绝规则不参与取值，匹配即拒绝。</p>
 *
 * @param maxRows             最大行数
 * @param maxBytes            最大字节数
 * @param maxExecutionSeconds 最长执行秒数
 * @param maskingLevel        字段脱敏级别
 * @author DataGate
 */
public record DecisionLimits(
    long maxRows,
    long maxBytes,
    long maxExecutionSeconds,
    MaskingLevel maskingLevel
) {
}
