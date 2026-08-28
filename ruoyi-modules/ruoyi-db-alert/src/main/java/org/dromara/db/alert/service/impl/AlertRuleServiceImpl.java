package org.dromara.db.alert.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.alert.evaluate.AlertRuleMatcher;
import org.dromara.db.alert.evaluate.AlertRuleMatcher.MatchResult;
import org.dromara.db.alert.mapper.DbAlertRuleMapper;
import org.dromara.db.alert.service.IAlertRuleService;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class AlertRuleServiceImpl implements IAlertRuleService {
    private final DbAlertRuleMapper ruleMapper;
    public AlertRuleServiceImpl(DbAlertRuleMapper ruleMapper) { this.ruleMapper = ruleMapper; }

    public List<DbAlertRule> list() {
        return ruleMapper.selectList(new LambdaQueryWrapper<DbAlertRule>().orderByDesc(DbAlertRule::getCreateTime));
    }
    public DbAlertRule create(DbAlertRule rule) {
        if (rule.getStatus() == null) rule.setStatus("ACTIVE");
        if (rule.getVersion() == null) rule.setVersion(1);
        rule.setCreateTime(new Date());
        ruleMapper.insert(rule);
        return rule;
    }
    public DbAlertRule update(DbAlertRule rule) {
        if (rule.getId() == null) throw new DbServiceException(DbErrorCode.ALERT_RULE_INVALID, "规则 ID 必填");
        rule.setUpdateTime(new Date());
        int rows = ruleMapper.updateById(rule);
        if (rows <= 0) throw new DbServiceException(DbErrorCode.WORKFLOW_STATE_CONFLICT, "规则版本已变化，请刷新");
        return ruleMapper.selectById(rule.getId());
    }
    public MatchResult test(Long ruleId, SlowMetricEvent sample) {
        DbAlertRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE, "规则不存在");
        return AlertRuleMatcher.evaluate(rule, sample);
    }
}
