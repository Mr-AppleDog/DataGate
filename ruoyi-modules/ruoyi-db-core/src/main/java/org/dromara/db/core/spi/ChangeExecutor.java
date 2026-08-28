package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ConnectionContext;

/**
 * 变更执行器（docs/06 §13、docs/02 §6.6，M5-02）。
 *
 * <p>使用专用变更账号（CredentialPurpose.CHANGE）逐语句执行已审批的不可变 SQL 快照；
 * 设置锁等待与执行超时；记录每条语句状态/影响行数/错误码/开始结束时间。
 * DDL 是否事务化按引擎判断；不做自动 SQL 优化或自动回滚承诺（docs/06 §13）。
 *
 * <p>执行器假设所有输入不可信：重新解析校验动作（CHANGE_DML/CHANGE_DDL），拒绝只读查询经此路径。</p>
 *
 * @author DataGate
 */
public interface ChangeExecutor {

    /**
     * 执行变更。
     *
     * @param req 已重新鉴权的变更执行请求（含锁定 SQL + 幂等键）
     * @param ctx 连接上下文（专用变更账号凭据 + 原始 SQL）
     * @return 变更结果（含逐语句结果 + 影响行数 + 终态）
     */
    ChangeResult execute(ChangeExecutionRequest req, ConnectionContext ctx);
}
