package org.dromara.db.connector.redis.support;

/**
 * Redis 执行限制（docs/06 §8.2 元素上限、§11 字节上限）。
 *
 * <p>由执行器依据 ExecutionPlan + 平台硬上限计算后下发给命令派发器；
 * 派发器据此对 SCAN COUNT / LRANGE 端点 / SMEMBERS 等无界命令施加硬截断。</p>
 *
 * @param maxRows   最大元素/行数
 * @param maxBytes  最大字节数
 * @param scanCount SCAN COUNT 硬上限（docs/06 §8.2）
 * @author DataGate
 */
public record RedisLimits(long maxRows, long maxBytes, int scanCount) {
}
