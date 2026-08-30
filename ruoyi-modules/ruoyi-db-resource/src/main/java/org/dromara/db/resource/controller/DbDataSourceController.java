package org.dromara.db.resource.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.resource.domain.DbMetadataSyncJob;
import org.dromara.db.resource.domain.bo.DbConnectionTestBo;
import org.dromara.db.resource.domain.bo.DbDataSourceBo;
import org.dromara.db.resource.domain.vo.DbDataSourceVo;
import org.dromara.db.resource.service.DataSourceConnectionTestService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.dromara.db.resource.service.IMetadataSyncService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据源管理（RES-002~004）。
 *
 * <p>只接受结构化字段；连接测试返回分项能力结果而非底层异常；
 * 所有写操作与状态流转由服务层状态机控制并写审计。</p>
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/datasource")
public class DbDataSourceController extends BaseController {

    private final IDbDataSourceService dataSourceService;
    private final IMetadataSyncService metadataSyncService;
    private final DataSourceConnectionTestService connectionTestService;

    /**
     * 分页查询数据源列表
     */
    @SaCheckPermission("db:datasource:list")
    @GetMapping("/list")
    public TableDataInfo<DbDataSourceVo> list(DbDataSourceBo bo, PageQuery pageQuery) {
        return dataSourceService.queryPageList(bo, pageQuery);
    }

    /**
     * 查询控制台可选数据源。服务端只返回 ACTIVE 状态，避免把不可执行状态暴露为可选项。
     */
    @SaCheckPermission("db:console:query")
    @GetMapping("/available")
    public R<List<DbDataSourceVo>> available() {
        return R.ok(dataSourceService.queryAvailableList());
    }

    /**
     * 查询数据源详情（不含任何秘密）
     */
    @SaCheckPermission("db:datasource:query")
    @GetMapping("/{id}")
    public R<DbDataSourceVo> getInfo(@PathVariable @NotNull Long id) {
        return R.ok(dataSourceService.queryVoById(id));
    }

    /**
     * 创建草稿数据源（创建前 SSRF/网络白名单校验）
     */
    @SaCheckPermission("db:datasource:add")
    @PostMapping
    public R<Long> add(@Validated @RequestBody DbDataSourceBo bo) {
        return R.ok(dataSourceService.createDraft(bo));
    }

    /**
     * 更新非秘密配置（乐观锁；主机/端口变更重新 SSRF 校验）
     */
    @SaCheckPermission("db:datasource:edit")
    @PutMapping
    public R<Void> edit(@Validated @RequestBody DbDataSourceBo bo) {
        return toAjax(dataSourceService.updateByBo(bo));
    }

    /**
     * 使用本次请求中的临时凭据测试尚未保存的结构化连接配置；临时密码不落库、不回显。
     */
    @SaCheckPermission("db:datasource:verify")
    @PostMapping("/test-connection")
    public R<ConnectionTestResult> testConnection(@Validated @RequestBody DbConnectionTestBo bo) {
        return R.ok(connectionTestService.test(bo));
    }

    /**
     * 连接测试：解密专用凭据（内存最短驻留），返回分项能力结果
     */
    @SaCheckPermission("db:datasource:verify")
    @PostMapping("/{id}/verify")
    public R<ConnectionTestResult> verify(@PathVariable @NotNull Long id) {
        return R.ok(dataSourceService.verify(id));
    }

    /**
     * 启用数据源（首次启用先同步元数据，失败时保持已验证状态）
     */
    @SaCheckPermission("db:datasource:enable")
    @PutMapping("/{id}/enable")
    public R<Void> enable(@PathVariable @NotNull Long id) {
        return toAjax(dataSourceService.enable(id));
    }

    /**
     * 禁用数据源（阻止新执行）
     */
    @SaCheckPermission("db:datasource:disable")
    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable @NotNull Long id) {
        return toAjax(dataSourceService.disable(id));
    }

    /**
     * 触发元数据同步（RES-005：仅 VERIFYING/ACTIVE 状态）
     */
    @SaCheckPermission("db:datasource:sync")
    @PostMapping("/{id}/sync")
    public R<DbMetadataSyncJob> sync(@PathVariable @NotNull Long id) {
        return R.ok(metadataSyncService.syncNow(id));
    }

    /**
     * 查询最近同步任务
     */
    @SaCheckPermission("db:datasource:query")
    @GetMapping("/{id}/sync-jobs")
    public R<List<DbMetadataSyncJob>> syncJobs(@PathVariable @NotNull Long id) {
        return R.ok(metadataSyncService.recentJobs(id, 10));
    }
}
