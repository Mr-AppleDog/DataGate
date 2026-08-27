package org.dromara.db.console.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 查询控制台请求（M2-04，docs/02 §11）。
 *
 * <p>客户端只提交结构化字段；userId/sessionId/sourceIp 由服务端从 Sa-Token 会话注入，
 * 禁止客户端伪造操作人身份。statement 经网关解析校验、纵深防御再解析。</p>
 *
 * @param dataSourceId  数据源
 * @param databaseName  目标库（null 用数据源默认库）
 * @param schemaName    目标 Schema（可空，PG）
 * @param statement     用户提交语句
 * @param maxRows       客户端申请行数上限（null 用默认 500）
 * @author DataGate
 */
public record QueryRequest(
    @NotNull Long dataSourceId,
    String databaseName,
    String schemaName,
    @NotBlank @Size(max = 1_000_000) String statement,
    Long maxRows
) {
}
