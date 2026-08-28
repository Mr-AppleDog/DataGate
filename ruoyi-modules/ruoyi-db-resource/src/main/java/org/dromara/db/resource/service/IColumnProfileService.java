package org.dromara.db.resource.service;

import org.dromara.db.resource.domain.DbColumnProfile;
import org.dromara.db.resource.domain.vo.DbColumnProfileVo;

import java.util.List;

/**
 * 列敏感策略管理（docs/04 §3.7、docs/10 M5-05 MASK-001）。
 *
 * <p>人工标签（MANUAL）由 DBA/安全确认；元数据重同步不覆盖 MANUAL 行。
 *
 * @author DataGate
 */
public interface IColumnProfileService {

    /** 查询单列策略 */
    DbColumnProfileVo get(Long resourceId);

    /** 按表资源列出其列策略（列资源 parent_id=tableResourceId） */
    List<DbColumnProfileVo> listByTable(Long tableResourceId);

    /** 人工确认/覆盖单列敏感标签（MANUAL，当前用户确认） */
    void setManualLabel(Long resourceId, String sensitivityLevel, String maskingType, String maskingConfig);

    /** 元数据重同步批量应用规则标签；跳过 MANUAL 行，返回实际写入数 */
    int applyRuleLabels(List<DbColumnProfile> ruleProfiles);
}
