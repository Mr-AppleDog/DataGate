package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ExportExecutionRequest;
import org.dromara.db.core.domain.ExportResult;

/**
 * 导出执行网关（docs/02 §6.6、docs/06 §12）。
 *
 * <p>独立于查询执行网关：流式读取已授权查询结果 → 服务端脱敏 → CSV 公式注入防护
 * → 加密对象存储；不向客户端直接吐结果，只在工单成功后生成一次性下载票据。
 * 实现假设所有输入不可信：执行前重新解析+重新鉴权+校验计划（纵深防御）。</p>
 *
 * @author DataGate
 */
public interface ExportExecutionGateway {

    /**
     * 执行导出。
     *
     * @param req 已重新鉴权的导出执行请求（含锁定 SQL + 脱敏上下文）
     * @return 导出结果（含加密对象键/哈希/DEK 引用，或失败错误码）
     */
    ExportResult execute(ExportExecutionRequest req);
}
