package org.dromara.db.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.resource.domain.vo.DbEnvironmentVo;
import org.dromara.db.resource.service.IDbEnvironmentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 环境管理（RES-001）
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/environment")
public class DbEnvironmentController extends BaseController {

    private final IDbEnvironmentService environmentService;

    /**
     * 查询启用中的环境列表（数据源表单下拉用）
     */
    @SaCheckPermission("db:datasource:list")
    @GetMapping("/list")
    public R<List<DbEnvironmentVo>> list() {
        return R.ok(environmentService.listActive());
    }
}
