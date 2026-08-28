package org.dromara.db.core.masking;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.MaskingConfig;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.spi.FieldMaskingEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认字段脱敏引擎（docs/06 §11、docs/10 M5-05）。
 *
 * <p>纯算法实现，无持久化/Web 依赖；可被连接器执行器、导出执行器与查询预览共用。
 * 失败关闭：任何掩码异常降级为保留长度等长全掩码，绝不泄露原值。</p>
 *
 * <p>掩码算法（确定性）：</p>
 * <ul>
 *   <li>PHONE：保留前 3 后 4，中间等长 *（138****5678）；</li>
 *   <li>ID_CARD：保留前 6 后 4，中间等长掩码（110101********234X）；</li>
 *   <li>BANK_CARD：保留前 4 后 4，中间等长掩码（6222********7890）；</li>
 *   <li>EMAIL：本地部分保留首字符，其余掩码，域名保留（a***@example.com）；</li>
 *   <li>ADDRESS：保留前 6 字符，其余等长掩码；</li>
 *   <li>CUSTOM：按 MaskingConfig 保留前后若干字符；</li>
 *   <li>NONE：原值透传。</li>
 * </ul>
 *
 * @author DataGate
 */
public class DefaultFieldMaskingEngine implements FieldMaskingEngine {

    private static final String MASK_CHAR = "*";
    private static final RowCell HIDDEN_CELL = new RowCell(null, false, null);

    @Override
    public RowCell mask(RowCell cell, ColumnMaskingPolicy policy, MaskingLevel level) {
        if (level == null) {
            level = MaskingLevel.MASKED;
        }
        if (cell == null) {
            return null;
        }
        // HIDDEN：整列不返回值（仅占位）
        if (level == MaskingLevel.HIDDEN) {
            return HIDDEN_CELL;
        }
        // UNMASKED：持有 COLUMN_UNMASK 授权，原值透传
        if (level == MaskingLevel.UNMASKED) {
            return cell;
        }
        // MASKED：无策略或非敏感列透传
        if (policy == null || !policy.isSensitive()) {
            return cell;
        }
        String value = cell.value();
        if (value == null) {
            return cell;
        }
        try {
            String masked = applyMasker(value, policy.maskingType(), policy.maskingConfig());
            return new RowCell(masked, cell.truncated(), cell.binarySummary());
        } catch (RuntimeException e) {
            // 失败关闭：全掩码，绝不泄露原值
            return new RowCell(fullMask(value), cell.truncated(), cell.binarySummary());
        }
    }

    @Override
    public List<RowCell> maskRow(List<RowCell> cells, List<ColumnMaskingPolicy> policies, List<MaskingLevel> levels) {
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        int n = cells.size();
        List<RowCell> out = new ArrayList<>(n);
        MaskingLevel rowDefault = firstNonNull(levels);
        for (int i = 0; i < n; i++) {
            RowCell cell = cells.get(i);
            ColumnMaskingPolicy p = (policies != null && i < policies.size()) ? policies.get(i) : null;
            MaskingLevel lvl = (levels != null && i < levels.size()) ? levels.get(i) : rowDefault;
            out.add(mask(cell, p, lvl));
        }
        return out;
    }

    // ====================== 掩码器 ======================

    String applyMasker(String value, MaskingType type, MaskingConfig config) {
        return switch (type) {
            case PHONE -> keepEnds(value, 3, 4);
            case ID_CARD -> keepEnds(value, 6, 4);
            case BANK_CARD -> keepEnds(value, 4, 4);
            case EMAIL -> maskEmail(value);
            case ADDRESS -> keepEnds(value, 6, 0);
            case CUSTOM -> keepEnds(value,
                config == null ? 0 : config.keepPrefix(),
                config == null ? 0 : config.keepSuffix(),
                config == null ? MASK_CHAR : config.maskChar());
            case NONE -> value;
        };
    }

    /**
     * 保留前 prefix 与后 suffix 字符，中间以 maskChar 等长掩码。
     * 值长度不足以同时保留前后时，仅保留前缀，剩余全掩码（绝不抛异常）。
     */
    private String keepEnds(String value, int prefix, int suffix) {
        return keepEnds(value, prefix, suffix, MASK_CHAR);
    }

    private String keepEnds(String value, int prefix, int suffix, String maskChar) {
        int len = value.length();
        if (len == 0) {
            return value;
        }
        if (prefix < 0) prefix = 0;
        if (suffix < 0) suffix = 0;
        if (prefix + suffix >= len) {
            // 值过短无法同时保留前后缀且留非空中段：全掩码，绝不泄露原值
            return repeat(maskChar, len);
        }
        String head = value.substring(0, prefix);
        String tail = value.substring(len - suffix);
        return head + repeat(maskChar, len - prefix - suffix) + tail;
    }

    private String maskEmail(String value) {
        int at = value.indexOf('@');
        if (at <= 0) {
            return keepEnds(value, 1, 0);
        }
        String local = value.substring(0, at);
        String domain = value.substring(at); // 含 @
        if (local.length() == 1) {
            return local + repeat(MASK_CHAR, 1) + domain;
        }
        return local.charAt(0) + repeat(MASK_CHAR, local.length() - 1) + domain;
    }

    /** 全掩码：保留长度，全部替换为掩码字符（失败关闭兜底）。 */
    private String fullMask(String value) {
        return repeat(MASK_CHAR, value.length());
    }

    private static String repeat(String ch, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    private static MaskingLevel firstNonNull(List<MaskingLevel> levels) {
        if (levels == null) {
            return MaskingLevel.MASKED;
        }
        for (MaskingLevel l : levels) {
            if (l != null) {
                return l;
            }
        }
        return MaskingLevel.MASKED;
    }
}
