package org.dromara.db.alert.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.alert.domain.DbAlertRule;
import org.dromara.db.alert.evaluate.AlertRuleMatcher.MatchResult;
import org.dromara.db.alert.service.IAlertRuleService;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/alert-rules")
public class AlertRuleController extends BaseController {
    private final IAlertRuleService ruleService;
    @SaCheckPermission("db:alert:rule:list") @GetMapping
    public R<List<DbAlertRule>> list() { return R.ok(ruleService.list()); }
    @SaCheckPermission("db:alert:rule:add") @PostMapping
    public R<DbAlertRule> create(@RequestBody DbAlertRule rule) { return R.ok(ruleService.create(rule)); }
    @SaCheckPermission("db:alert:rule:edit") @PutMapping
    public R<DbAlertRule> update(@RequestBody DbAlertRule rule) { return R.ok(ruleService.update(rule)); }
    @SaCheckPermission("db:alert:rule:test") @PostMapping("/{id}/test")
    public R<MatchResult> test(@PathVariable Long id, @RequestBody SlowMetricEvent sample) { return R.ok(ruleService.test(id, sample)); }
}
