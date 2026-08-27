package org.dromara.db.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.db.workflow.domain.bo.GrantApplyBo;
import org.dromara.db.workflow.domain.bo.GrantApproveBo;
import org.dromara.db.workflow.domain.vo.GrantApplicationVo;
import org.dromara.db.workflow.service.GrantApplicationService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询权限审批 REST（M2-02，docs/03 §10.1）。
 *
 * <p>申请→审批→（批准生成 Grant / 拒绝不生成）。申请人不能审批本人申请（服务端强制）。</p>
 *
 * @author DataGate
 */
@Validated
@RestController
@RequestMapping("/db/workflow")
@RequiredArgsConstructor
public class DbWorkflowController {

    private final GrantApplicationService applicationService;

    /** 申请人提交查询权限申请 */
    @SaCheckPermission("db:workflow:apply")
    @PostMapping("/apply")
    public R<Long> apply(@Validated @RequestBody GrantApplyBo bo) {
        return R.ok(applicationService.apply(bo));
    }

    /** 审批人批准（生成授权） */
    @SaCheckPermission("db:workflow:approve")
    @PostMapping("/approve")
    public R<Void> approve(@Validated @RequestBody GrantApproveBo bo) {
        applicationService.approve(bo);
        return R.ok();
    }

    /** 审批人拒绝（不生成授权） */
    @SaCheckPermission("db:workflow:approve")
    @PostMapping("/reject")
    public R<Void> reject(@Validated @RequestBody GrantApproveBo bo) {
        applicationService.reject(bo);
        return R.ok();
    }

    /** 申请人撤销（不生成授权） */
    @SaCheckPermission("db:workflow:apply")
    @PostMapping("/cancel")
    public R<Void> cancel(@Validated @RequestBody GrantApproveBo bo) {
        applicationService.cancel(bo);
        return R.ok();
    }

    /** 申请单列表（普通用户查本人，管理员查全部） */
    @SaCheckPermission("db:workflow:list")
    @GetMapping("/list")
    public TableDataInfo<GrantApplicationVo> list(PageQuery pageQuery) {
        return applicationService.pageList(pageQuery);
    }
}
