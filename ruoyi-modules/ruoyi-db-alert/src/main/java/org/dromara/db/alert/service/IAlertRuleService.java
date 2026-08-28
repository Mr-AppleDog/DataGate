package org.dromara.db.alert.service;

import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.alert.evaluate.AlertRuleMatcher.MatchResult;
import org.dromara.db.core.domain.SlowMetricEvent;

import java.util.List;

public interface IAlertRuleService {
    List<DbAlertRule> list();
    DbAlertRule create(DbAlertRule rule);
    DbAlertRule update(DbAlertRule rule);
    MatchResult test(Long ruleId, SlowMetricEvent sample);
}
