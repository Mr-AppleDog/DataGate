package org.dromara.db.core.domain;

import org.dromara.db.core.enums.MaskingType;
import org.dromara.db.core.enums.SensitivityLevel;

/**
 * 列脱敏策略（docs/04 §3.7 dbg_column_profile 的不可变快照）。
 *
 * <p>由 dbg_column_profile 表加载：每个 COLUMN 资源绑定一个敏感等级与脱敏类型。
 * 元数据重新同步不得覆盖人工确认（classification_source=MANUAL）的标签（docs/04 §3.7、docs/10 M5-05）。</p>
 *
 * <p>本对象仅描述静态策略，不含运行时授权级别——后者由鉴权决定（MaskingLevel），
 * 引擎按 (policy, level) 组合应用，保证查询预览、普通结果与导出使用同一服务端脱敏引擎（docs/10 M5-05）。</p>
 *
 * @param resourceId            列资源 ID（dbg_resource.id，type=COLUMN）
 * @param columnName            列名/规范名
 * @param sensitivityLevel      敏感等级
 * @param maskingType           脱敏类型（CUSTOM 用 maskingConfig）
 * @param maskingConfig         自定义掩码配置（非 CUSTOM 时可空）
 * @param classificationSource  分类来源：MANUAL/RULE/IMPORT（MANUAL 不被重同步覆盖）
 * @author DataGate
 */
public record ColumnMaskingPolicy(
    Long resourceId,
    String columnName,
    SensitivityLevel sensitivityLevel,
    MaskingType maskingType,
    MaskingConfig maskingConfig,
    String classificationSource
) {

    public ColumnMaskingPolicy {
        if (sensitivityLevel == null) {
            sensitivityLevel = SensitivityLevel.PUBLIC;
        }
        if (maskingType == null) {
            maskingType = MaskingType.NONE;
        }
    }

    /**
     * 是否人工确认标签（重同步不可覆盖）。
     */
    public boolean isManual() {
        return "MANUAL".equals(classificationSource);
    }

    /**
     * 是否敏感列（达到 SENSITIVE 及以上且非 NONE 类型才需脱敏）。
     */
    public boolean isSensitive() {
        return sensitivityLevel.ordinal() >= SensitivityLevel.SENSITIVE.ordinal()
            && maskingType != MaskingType.NONE;
    }
}
