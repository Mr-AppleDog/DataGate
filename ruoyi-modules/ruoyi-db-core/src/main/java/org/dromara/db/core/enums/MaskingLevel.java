package org.dromara.db.core.enums;

/**
 * 字段脱敏级别（docs/03 第 7.4 节 limits.maskingLevel）。
 *
 * @author DataGate
 */
public enum MaskingLevel {

    /**
     * 持有 COLUMN_UNMASK 授权，返回明文
     */
    UNMASKED,

    /**
     * 默认，服务端流式脱敏后吐出
     */
    MASKED,

    /**
     * 高敏感，整列不返回值（仅占位）
     */
    HIDDEN
}
