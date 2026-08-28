package org.dromara.db.observability.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.observability.domain.DbSlowSource;
import org.dromara.db.observability.service.ISlowQueryCollectionService;
import org.dromara.db.observability.service.ISlowQueryCollectionService.CollectResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 慢查询采集器 API（docs/05 §2.9）。
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/slow-collectors")
public class SlowCollectorController extends BaseController {

    private final ISlowQueryCollectionService collectionService;

    @SaCheckPermission("db:slow:collector:list")
    @GetMapping
    public R<List<DbSlowSource>> list() {
        return R.ok(collectionService.listCollectors());
    }

    @SaCheckPermission("db:slow:collector:run")
    @PostMapping("/{id}/run")
    public R<CollectResult> run(@PathVariable @NotNull Long id) {
        return R.ok(collectionService.collectOne(id));
    }
}
