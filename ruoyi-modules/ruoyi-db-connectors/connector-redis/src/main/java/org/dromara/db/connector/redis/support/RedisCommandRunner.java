package org.dromara.db.connector.redis.support;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.security.SecretValue;

import java.util.List;

/**
 * Redis 命令派发器（docs/06 §8.1：结构化参数派发，不接受原始文本拼接）。
 *
 * <p>实现方以结构化 verb + args 调用目标 Redis（Lettuce dispatch），
 * 集群 MOVED/ASK 由客户端拓扑处理（docs/06 §8.2 集群受控）。
 * 默认实现 {@code LettuceRedisCommandRunner}；测试注入桩以验证流式限制。</p>
 *
 * <p>派发器职责：执行单条已校验的只读命令，按 {@link RedisLimits} 施以硬截断，
 * 塑形为 {@link RedisResponse}。不重新解析/鉴权（执行器已做纵深再解析）。</p>
 *
 * @author DataGate
 */
@FunctionalInterface
public interface RedisCommandRunner {

    /**
     * 执行单条 Redis 命令。
     *
     * @param profile 连接配置
     * @param secret  秘密（密码），使用后由执行器销毁
     * @param verb    命令动词（大写）
     * @param args    结构化参数
     * @param limits  执行限制
     * @return 统一响应（列头 + 行集 + 截断标记）
     */
    RedisResponse run(ConnectionProfile profile, SecretValue secret, String verb,
                      List<String> args, RedisLimits limits) throws Exception;
}
