package org.dromara.db.observability.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.governance.DeterministicAnalyzer.AnalysisResult;
import org.dromara.db.observability.service.ISlowQueryAnalysisService;
import org.dromara.db.observability.service.ISlowQueryGovernanceService;
import org.dromara.db.observability.service.ISlowQueryGovernanceService.GovernanceDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 慢查询指纹治理 API（docs/05 §2.9 + docs/07 §10）。
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/slow-query-fingerprints")
public class SlowQueryFingerprintController extends BaseController {

    private final ISlowQueryGovernanceService governanceService;
    private final ISlowQueryAnalysisService analysisService;

    @SaCheckPermission("db:slow:list")
    @GetMapping
    public R<List<DbSlowFingerprint>> list(@RequestParam(required = false) String governanceStatus,
                                           @RequestParam(required = false) Long dataSourceId,
                                           @RequestParam(defaultValue = "20") int limit) {
        return R.ok(governanceService.listFingerprints(governanceStatus, dataSourceId, limit));
    }

    @SaCheckPermission("db:slow:query")
    @GetMapping("/{id}")
    public R<GovernanceDetail> getDetail(@PathVariable @NotNull Long id,
                                         @RequestParam(defaultValue = "20") int sampleLimit) {
        return R.ok(governanceService.getDetail(id, sampleLimit));
    }

    @SaCheckPermission("db:slow:claim")
    @PostMapping("/{id}/claim")
    public R<DbSlowFingerprint> claim(@PathVariable @NotNull Long id,
                                      @RequestParam @NotNull Long assigneeId) {
        return R.ok(governanceService.claim(id, assigneeId, LoginHelper.getUserId()));
    }

    @SaCheckPermission("db:slow:transition")
    @PostMapping("/{id}/transition")
    public R<DbSlowFingerprint> transition(@PathVariable @NotNull Long id,
                                            @RequestParam @NotNull String toStatus,
                                            @RequestParam @NotNull Integer version,
                                            @RequestParam(required = false) String comment) {
        return R.ok(governanceService.transition(id, toStatus, version, comment, LoginHelper.getUserId()));
    }

    @SaCheckPermission("db:slow:comment")
    @PostMapping("/{id}/comments")
    public R<Void> comment(@PathVariable @NotNull Long id, @RequestParam @NotNull String text) {
        governanceService.comment(id, text, LoginHelper.getUserId());
        return R.ok();
    }

    @SaCheckPermission("db:slow:query")
    @GetMapping("/{id}/analysis")
    public R<AnalysisResult> analyze(@PathVariable @NotNull Long id) {
        return R.ok(analysisService.analyze(id));
    }
}
