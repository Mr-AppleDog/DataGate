package org.dromara.db.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.resource.domain.vo.DbResourceVo;
import org.dromara.db.resource.service.IMetadataSyncService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源目录查询（docs/03 第 3 节）。按父节点分层懒加载，只读。
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/resource")
public class DbResourceController extends BaseController {

    private final IMetadataSyncService metadataSyncService;

    /**
     * 按父节点查询资源（parentId=0 为数据库根层）
     */
    @SaCheckPermission("db:resource:list")
    @GetMapping("/list")
    public R<List<DbResourceVo>> list(@RequestParam @NotNull Long dataSourceId,
                                      @RequestParam(required = false) Long parentId) {
        return R.ok(metadataSyncService.listResources(dataSourceId, parentId));
    }
}
