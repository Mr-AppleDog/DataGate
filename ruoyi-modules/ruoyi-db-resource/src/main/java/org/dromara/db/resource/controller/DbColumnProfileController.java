package org.dromara.db.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.resource.domain.vo.DbColumnProfileVo;
import org.dromara.db.resource.service.IColumnProfileService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 列敏感策略管理（docs/04 §3.7、docs/10 M5-05 MASK-001）。
 *
 * <p>DBA/安全人工确认列敏感等级与脱敏类型（MANUAL）；元数据重同步不覆盖 MANUAL 标签。
 * 不暴露凭据/原值；脱敏本身在服务端流式阶段完成。</p>
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/column-profile")
public class DbColumnProfileController extends BaseController {

    private final IColumnProfileService columnProfileService;

    /**
     * 查询单列敏感策略
     */
    @SaCheckPermission("db:column:query")
    @GetMapping("/{resourceId}")
    public R<DbColumnProfileVo> get(@PathVariable @NotNull Long resourceId) {
        return R.ok(columnProfileService.get(resourceId));
    }

    /**
     * 按表资源列出其列策略
     */
    @SaCheckPermission("db:column:query")
    @GetMapping("/list-by-table/{tableResourceId}")
    public R<List<DbColumnProfileVo>> listByTable(@PathVariable @NotNull Long tableResourceId) {
        return R.ok(columnProfileService.listByTable(tableResourceId));
    }

    /**
     * 人工确认/覆盖单列敏感标签（MANUAL）
     *
     * @param resourceId 列资源 ID
     * @param body       {sensitivityLevel, maskingType, maskingConfig}
     */
    @SaCheckPermission("db:column:mask")
    @PutMapping("/{resourceId}")
    public R<Void> setManualLabel(@PathVariable @NotNull Long resourceId,
                                  @RequestBody Map<String, String> body) {
        String level = body.get("sensitivityLevel");
        String type = body.get("maskingType");
        String config = body.get("maskingConfig");
        if (level == null || level.isBlank()) {
            throw new org.dromara.common.core.exception.ServiceException("sensitivityLevel 不能为空");
        }
        if (type == null || type.isBlank()) {
            type = "NONE";
        }
        columnProfileService.setManualLabel(resourceId, level, type, config);
        return R.ok();
    }
}
