package org.dromara.db.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.workflow.domain.bo.EmergencyApplyBo;
import org.dromara.db.workflow.domain.bo.EmergencyApproveBo;
import org.dromara.db.workflow.domain.vo.DbEmergencyAccessVo;
import org.dromara.db.workflow.service.EmergencyAccessService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 紧急访问 REST（docs/03 §10.4，M5-04）。
 *
 * <p>双人审批 + 2h 临时授权 + TOTP + 事件编号 + 自动到期 + 事后复盘；不续期。
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/emergency")
public class DbEmergencyAccessController extends BaseController {

    private final EmergencyAccessService emergencyAccessService;

    /** 申请紧急访问 */
    @SaCheckPermission("db:emergency:apply")
    @PostMapping("/access")
    public R<Long> apply(@Validated @RequestBody EmergencyApplyBo bo) {
        return R.ok(emergencyAccessService.apply(bo));
    }

    /** 审批通过（办理当前节点） */
    @SaCheckPermission("db:emergency:approve")
    @PostMapping("/access/{id}:approve")
    public R<Void> approve(@PathVariable @NotNull Long id, @RequestBody EmergencyApproveBo bo) {
        bo.setAccessId(id);
        emergencyAccessService.approve(bo);
        return R.ok();
    }

    /** 拒绝 */
    @SaCheckPermission("db:emergency:approve")
    @PostMapping("/access/{id}:reject")
    public R<Void> reject(@PathVariable @NotNull Long id, @RequestBody EmergencyApproveBo bo) {
        bo.setAccessId(id);
        emergencyAccessService.reject(bo);
        return R.ok();
    }

    /** 申请人撤销 */
    @SaCheckPermission("db:emergency:apply")
    @PostMapping("/access/{id}:cancel")
    public R<Void> cancel(@PathVariable @NotNull Long id, @RequestBody EmergencyApproveBo bo) {
        bo.setAccessId(id);
        emergencyAccessService.cancel(bo);
        return R.ok();
    }

    /** 撤销已激活的紧急授权（即时失效） */
    @SaCheckPermission("db:emergency:revoke")
    @PostMapping("/access/{id}:revoke")
    public R<Void> revoke(@PathVariable @NotNull Long id, @RequestBody EmergencyApproveBo bo) {
        bo.setAccessId(id);
        emergencyAccessService.revoke(bo);
        return R.ok();
    }

    /** 事后复盘（开通后 24h 内） */
    @SaCheckPermission("db:emergency:apply")
    @PostMapping("/access/{id}:postmortem")
    public R<Void> postMortem(@PathVariable @NotNull Long id, @RequestBody EmergencyApproveBo bo) {
        bo.setAccessId(id);
        emergencyAccessService.postMortem(bo);
        return R.ok();
    }

    /** 查询 */
    @SaCheckPermission("db:emergency:query")
    @GetMapping("/access/{id}")
    public R<DbEmergencyAccessVo> get(@PathVariable @NotNull Long id) {
        return R.ok(emergencyAccessService.getById(id));
    }

    /** 列表 */
    @SaCheckPermission("db:emergency:query")
    @GetMapping("/access")
    public TableDataInfo<DbEmergencyAccessVo> list(PageQuery pageQuery) {
        return emergencyAccessService.pageList(pageQuery);
    }
}
