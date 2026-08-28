package org.dromara.db.core.enums;

/**
 * 字段脱敏类型（docs/04 §3.7 dbg_column_profile.masking_type）。
 *
 * <p>每种类型对应一个确定性的掩码算法（见 DefaultFieldMaskingEngine），
 * 服务端流式阶段完成，前端永远接触不到原值（docs/06 §11、docs/03 §7.4）。</p>
 *
 * @author DataGate
 */
public enum MaskingType {

    /** 手机号：保留前 3 后 4，中间掩码（138****5678） */
    PHONE,

    /** 身份证号：保留前 6 后 4，中间掩码（110101********234X） */
    ID_CARD,

    /** 银行卡号：保留前 4 后 4，中间掩码（6222********7890） */
    BANK_CARD,

    /** 邮箱：本地部分保留首字符，其余掩码，域名保留（a***@example.com） */
    EMAIL,

    /** 地址：保留前 6 字符，其余掩码 */
    ADDRESS,

    /** 自定义：按 MaskingConfig 保留前后若干字符 */
    CUSTOM,

    /** 显式非敏感：不脱敏，原值透传 */
    NONE
}
