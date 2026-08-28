package org.dromara.db.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.workflow.domain.bo.ChangeApplyBo;
import org.dromara.db.workflow.domain.bo.ChangeApproveBo;
import org.dromara.db.workflow.domain.bo.ChangeScheduleBo;
import org.dromara.db.workflow.domain.vo.DbChangeExecutionVo;
import org.dromara.db.workflow.domain.vo.DbChangeOrderVo;
import org.dromara.db.workflow.service.ChangeOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL 变更工单 REST（docs/05 §2.8，M5-02c）。
 *
 * <p>DML/DDL 不可变快照 + 两级审批 + 执行窗口 + 专用变更账号 + 幂等逐语句执行。
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/change")
public class DbChangeOrderController extends BaseController {

    private final ChangeOrderService changeOrderService;

    /** 创建变更草稿 */
    @SaCheckPermission("db:change:apply")
    @PostMapping("/orders")
    public R<Long> create(@Validated @RequestBody ChangeApplyBo bo) {
        return R.ok(changeOrderService.create(bo));
    }

    /** 预检查（解析+影响+风险） */
    @SaCheckPermission("db:change:apply")
    @PostMapping("/orders/{id}:precheck")
    public R<Void> precheck(@PathVariable @NotNull Long id) {
        changeOrderService.precheck(id);
        return R.ok();
    }

    /** 提交审批（启动两级审批流） */
    @SaCheckPermission("db:change:apply")
    @PostMapping("/orders/{id}:submit")
    public R<Void> submit(@PathVariable @NotNull Long id) {
        changeOrderService.submit(id);
        return R.ok();
    }

    /** 审批通过（办理当前节点） */
    @SaCheckPermission("db:change:approve")
    @PostMapping("/orders/{id}:approve")
    public R<Void> approve(@PathVariable @NotNull Long id, @RequestBody ChangeApproveBo bo) {
        bo.setOrderId(id);
        changeOrderService.approve(bo);
        return R.ok();
    }

    /** 审批拒绝 */
    @SaCheckPermission("db:change:approve")
    @PostMapping("/orders/{id}:reject")
    public R<Void> reject(@PathVariable @NotNull Long id, @RequestBody ChangeApproveBo bo) {
        bo.setOrderId(id);
        changeOrderService.reject(bo);
        return R.ok();
    }

    /** 申请人撤销 */
    @SaCheckPermission("db:change:apply")
    @PostMapping("/orders/{id}:cancel")
    public R<Void> cancel(@PathVariable @NotNull Long id, @RequestBody ChangeApproveBo bo) {
        bo.setOrderId(id);
        changeOrderService.cancel(bo);
        return R.ok();
    }

    /** DBA 设置执行窗口 */
    @SaCheckPermission("db:change:approve")
    @PostMapping("/orders/{id}:schedule")
    public R<Void> schedule(@PathVariable @NotNull Long id, @Validated @RequestBody ChangeScheduleBo bo) {
        bo.setOrderId(id);
        changeOrderService.schedule(bo);
        return R.ok();
    }

    /** 执行（窗口到达+二次认证后；幂等） */
    @SaCheckPermission("db:change:execute")
    @PostMapping("/orders/{id}:execute")
    public R<ChangeResult> execute(@PathVariable @NotNull Long id) {
        return R.ok(changeOrderService.execute(id));
    }

    /** 查询工单 */
    @SaCheckPermission("db:change:query")
    @GetMapping("/orders/{id}")
    public R<DbChangeOrderVo> get(@PathVariable @NotNull Long id) {
        return R.ok(changeOrderService.getById(id));
    }

    /** 工单列表 */
    @SaCheckPermission("db:change:query")
    @GetMapping("/orders")
    public TableDataInfo<DbChangeOrderVo> list(PageQuery pageQuery) {
        return changeOrderService.pageList(pageQuery);
    }

    /** 执行尝试历史 */
    @SaCheckPermission("db:change:query")
    @GetMapping("/orders/{id}/executions")
    public R<List<DbChangeExecutionVo>> executions(@PathVariable @NotNull Long id) {
        return R.ok(changeOrderService.listExecutions(id));
    }
}
