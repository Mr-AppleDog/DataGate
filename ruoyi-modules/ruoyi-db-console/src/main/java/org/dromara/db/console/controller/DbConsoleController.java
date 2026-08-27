package org.dromara.db.console.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.db.console.domain.QueryRequest;
import org.dromara.db.console.domain.QueryResultView;
import org.dromara.db.console.service.DbConsoleService;
import org.dromara.db.executor.domain.QueryExecutionRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询控制台 REST（M2-04，docs/02 §6.5/§11）。
 *
 * <p>操作人身份由服务端从 Sa-Token 注入，客户端不得伪造 userId/sessionId/sourceIp。
 * 首版同步返回有界结果（500 行）；长查询 SSE/WebSocket 留后续切片。</p>
 *
 * @author DataGate
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/db/console")
public class DbConsoleController extends BaseController {

    private final DbConsoleService consoleService;

    /**
     * 提交查询（生产控制台默认单条、只读、有界）
     */
    @SaCheckPermission("db:console:query")
    @PostMapping("/query")
    public R<QueryResultView> query(@Validated @RequestBody QueryRequest req) {
        QueryExecutionRequest execReq = new QueryExecutionRequest(
            LoginHelper.getUserId(),
            sessionId(),
            ServletUtils.getClientIP(),
            req.dataSourceId(),
            req.databaseName(),
            req.schemaName(),
            req.statement(),
            req.maxRows(),
            null);
        return R.ok(consoleService.execute(execReq));
    }

    /**
     * 取消正在运行的执行（幂等）
     */
    @SaCheckPermission("db:console:cancel")
    @GetMapping("/cancel/{executionNo}")
    public R<Void> cancel(@PathVariable @NotBlank String executionNo) {
        consoleService.cancel(executionNo);
        return R.ok();
    }

    private static String sessionId() {
        try {
            String token = StpUtil.getTokenValue();
            return token == null ? "" : token;
        } catch (Exception e) {
            return "";
        }
    }
}
