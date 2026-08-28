package org.dromara.db.core.enums;

/**
 * 列敏感等级（docs/04 §3.7 dbg_column_profile.sensitivity_level）。
 *
 * <p>等级从低到高：PUBLIC &lt; INTERNAL &lt; SENSITIVE &lt; RESTRICTED。
 * 无法可靠判断来源的表达式，在生产环境按最高等级（RESTRICTED）处理（docs/06 §11）。</p>
 *
 * @author DataGate
 */
public enum SensitivityLevel {

    PUBLIC,
    INTERNAL,
    SENSITIVE,
    RESTRICTED;

    /**
     * 取两者中更严格（更高）的等级（docs/03 §7.3 限制合并）。
     */
    public static SensitivityLevel moreRestrictive(SensitivityLevel a, SensitivityLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
