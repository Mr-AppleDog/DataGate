package org.dromara.db.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.workflow.domain.bo.ExportApplyBo;
import org.dromara.db.workflow.domain.bo.ExportApproveBo;
import org.dromara.db.workflow.domain.vo.DbExportJobVo;
import org.dromara.db.workflow.service.ExportJobService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 导出工单 REST（docs/05 §2.7，M5-01c）。
 *
 * <p>EXPORT 独立权限 + 两级审批 + 一次性下载票据。不返回永久对象存储 URL。
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/export")
public class DbExportJobController extends BaseController {

    private final ExportJobService exportJobService;

    /** 创建导出申请 */
    @SaCheckPermission("db:export:apply")
    @PostMapping("/requests")
    public R<Long> apply(@Validated @RequestBody ExportApplyBo bo) {
        return R.ok(exportJobService.apply(bo));
    }

    /** 查询工单状态 */
    @SaCheckPermission("db:export:query")
    @GetMapping("/jobs/{id}")
    public R<DbExportJobVo> get(@PathVariable @NotNull Long id) {
        return R.ok(exportJobService.getById(id));
    }

    /** 工单列表 */
    @SaCheckPermission("db:export:query")
    @GetMapping("/jobs")
    public TableDataInfo<DbExportJobVo> list(PageQuery pageQuery) {
        return exportJobService.pageList(pageQuery);
    }

    /** 审批通过（办理当前节点） */
    @SaCheckPermission("db:export:approve")
    @PostMapping("/jobs/{id}:approve")
    public R<Void> approve(@PathVariable @NotNull Long id, @RequestBody ExportApproveBo bo) {
        bo.setJobId(id);
        exportJobService.approve(bo);
        return R.ok();
    }

    /** 审批拒绝 */
    @SaCheckPermission("db:export:approve")
    @PostMapping("/jobs/{id}:reject")
    public R<Void> reject(@PathVariable @NotNull Long id, @RequestBody ExportApproveBo bo) {
        bo.setJobId(id);
        exportJobService.reject(bo);
        return R.ok();
    }

    /** 申请人撤销 */
    @SaCheckPermission("db:export:apply")
    @PostMapping("/jobs/{id}:cancel")
    public R<Void> cancel(@PathVariable @NotNull Long id, @RequestBody ExportApproveBo bo) {
        bo.setJobId(id);
        exportJobService.cancel(bo);
        return R.ok();
    }

    /** 生成一次性下载票据（5min，单次） */
    @SaCheckPermission("db:export:download")
    @PostMapping("/jobs/{id}:download-ticket")
    public R<String> downloadTicket(@PathVariable @NotNull Long id) {
        return R.ok(exportJobService.issueDownloadTicket(id));
    }

    /** 凭票据下载（一次性，计次后失效票据） */
    @SaCheckPermission("db:export:download")
    @GetMapping("/downloads/{ticket}")
    public void download(@PathVariable @NotNull String ticket, HttpServletResponse response) {
        try (InputStream in = exportJobService.openDownload(ticket);
             OutputStream out = response.getOutputStream()) {
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"datagate-export.csv\"");
            in.transferTo(out);
            out.flush();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("下载失败");
        }
    }
}
