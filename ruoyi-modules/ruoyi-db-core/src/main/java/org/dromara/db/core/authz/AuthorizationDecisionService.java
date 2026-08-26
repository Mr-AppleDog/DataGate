package org.dromara.db.core.authz;

/**
 * 资源授权判定服务（docs/03 第 7 节、docs/02 第 6.3 节）。
 *
 * <p>实现由 db-auth 提供；console/orchestrator 调用。
 * 逐资源判定，任一资源被拒绝则整个请求拒绝（docs/06 第 4 节 step 7）。
 * 默认拒绝、显式拒绝优先；失败关闭。</p>
 *
 * <p>并行冻结（ADR-007）：本接口在 M2/M3 并行期间稳定，变更须经 ADR 修订。</p>
 *
 * @author DataGate
 */
public interface AuthorizationDecisionService {

    /**
     * 单资源授权判定。
     *
     * @param request 判定请求（资源 ID 与动作由服务端解析得到）
     * @return 判定响应；allowed=false 时 limits 为 null
     */
    AccessDecision decide(DecisionRequest request);
}
