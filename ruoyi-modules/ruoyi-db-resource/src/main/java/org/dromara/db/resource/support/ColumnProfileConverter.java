package org.dromara.db.resource.support;

import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.MaskingConfig;
import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;
import org.dromara.db.resource.domain.DbColumnProfile;

/**
 * 列敏感策略转换器（docs/04 §3.7）。
 *
 * <p>纯静态方法，可在无 Spring/DB 环境下单元测试：
 * DbColumnProfile（持久化行） <-> ColumnMaskingPolicy（引擎契约），
 * 以及元数据重同步是否保留人工标签（MANUAL 不被覆盖）。</p>
 *
 * @author DataGate
 */
public final class ColumnProfileConverter {

    private ColumnProfileConverter() {
    }

    /**
     * 持久化行 -> 引擎策略契约。
     */
    public static ColumnMaskingPolicy toPolicy(DbColumnProfile p) {
        if (p == null) {
            return null;
        }
        return new ColumnMaskingPolicy(
            p.getResourceId(),
            null,
            safeLevel(p.getSensitivityLevel()),
            safeType(p.getMaskingType()),
            parseConfig(p.getMaskingConfig()),
            p.getClassificationSource()
        );
    }

    /**
     * 元数据重同步是否保留该行人工标签（docs/04 §3.7、docs/10 M5-05）。
     * MANUAL 行不被重同步覆盖；非 MANUAL（RULE/IMPORT/null）允许覆盖。
     */
    public static boolean shouldPreserveManual(DbColumnProfile existing) {
        return existing != null && "MANUAL".equals(existing.getClassificationSource());
    }

    /**
     * 解析自定义掩码配置 JSON（keepPrefix/keepSuffix/maskChar）；失败返回 null（CUSTOM 降级全掩码兜底）。
     */
    public static MaskingConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode n = m.readTree(json);
            int prefix = n.has("keepPrefix") ? n.get("keepPrefix").asInt() : 0;
            int suffix = n.has("keepSuffix") ? n.get("keepSuffix").asInt() : 0;
            String ch = n.has("maskChar") ? n.get("maskChar").asText() : "*";
            return new MaskingConfig(prefix, suffix, ch);
        } catch (Exception e) {
            return null;
        }
    }

    private static SensitivityLevel safeLevel(String s) {
        if (s == null) {
            return SensitivityLevel.PUBLIC;
        }
        try {
            return SensitivityLevel.valueOf(s);
        } catch (IllegalArgumentException e) {
            return SensitivityLevel.PUBLIC;
        }
    }

    private static MaskingType safeType(String s) {
        if (s == null) {
            return MaskingType.NONE;
        }
        try {
            return MaskingType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return MaskingType.NONE;
        }
    }
}
