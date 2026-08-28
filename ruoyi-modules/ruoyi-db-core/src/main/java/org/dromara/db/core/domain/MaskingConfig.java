package org.dromara.db.core.domain;

/**
 * 自定义脱敏配置（docs/04 §3.7 dbg_column_profile.masking_config，MaskingType.CUSTOM）。
 *
 * <p>保留值的前 keepPrefix 与后 keepSuffix 字符，中间以 maskChar 掩等长掩码。
 * 非负整数；maskChar 默认 *，长度恒为 1。</p>
 *
 * @param keepPrefix 保留前缀字符数（&gt;=0）
 * @param keepSuffix 保留后缀字符数（&gt;=0）
 * @param maskChar  掩码字符（默认 *）
 * @author DataGate
 */
public record MaskingConfig(int keepPrefix, int keepSuffix, String maskChar) {

    public MaskingConfig {
        if (keepPrefix < 0) {
            keepPrefix = 0;
        }
        if (keepSuffix < 0) {
            keepSuffix = 0;
        }
        if (maskChar == null || maskChar.isEmpty()) {
            maskChar = "*";
        } else if (maskChar.length() > 1) {
            maskChar = maskChar.substring(0, 1);
        }
    }

    public MaskingConfig(int keepPrefix, int keepSuffix) {
        this(keepPrefix, keepSuffix, "*");
    }
}
