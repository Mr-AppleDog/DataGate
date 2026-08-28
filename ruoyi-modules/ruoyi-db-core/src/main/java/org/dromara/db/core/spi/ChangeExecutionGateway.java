package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;

/**
 * 变更执行网关（docs/02 §6.6、docs/06 §13，M5-02）。
 *
 * <p>编排：解析数据源/专用变更凭据 → 校验执行窗口与审批 → 调连接器变更执行器逐语句执行
 * → 记录执行尝试（幂等）+ 审计。失败关闭：数据源未启用/凭据缺失/非变更语句/窗口未到 → FAILED。
 *
 * @author DataGate
 */
public interface ChangeExecutionGateway {

    ChangeResult execute(ChangeExecutionRequest req);
}
