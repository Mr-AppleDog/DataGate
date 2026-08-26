package org.dromara.db.core.domain;

import org.dromara.db.core.enums.ConnectorCapability;

import java.time.Duration;
import java.util.Set;

/**
 * 连接测试结果（docs/10 M1-03：返回分项能力而非底层异常）。
 *
 * @param success      是否成功
 * @param serverVersion 服务端版本（成功时）
 * @param capabilities 探测到的能力
 * @param latency      往返耗时
 * @param errorCode    失败时的平台标准错误码名（不携带底层异常堆栈/连接串）
 * @param errorSummary 遮蔽后的错误摘要
 * @author DataGate
 */
public record ConnectionTestResult(
    boolean success,
    String serverVersion,
    Set<ConnectorCapability> capabilities,
    Duration latency,
    String errorCode,
    String errorSummary
) {

    public static ConnectionTestResult ok(String serverVersion, Set<ConnectorCapability> capabilities, Duration latency) {
        return new ConnectionTestResult(true, serverVersion, capabilities, latency, null, null);
    }

    public static ConnectionTestResult fail(String errorCode, String errorSummary) {
        return new ConnectionTestResult(false, null, Set.of(), null, errorCode, errorSummary);
    }
}
