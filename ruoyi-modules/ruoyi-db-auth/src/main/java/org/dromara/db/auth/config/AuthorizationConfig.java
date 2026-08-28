package org.dromara.db.auth.config;

import org.dromara.db.auth.policy.DecisionCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 鉴权环境限制与决策缓存装配（docs/03 第 7.3 节、docs/06 §16 撤权 60s）。
 *
 * <p>从 application.yml 读取，缺省取生产默认。全部可配置。
 * {@link DecisionCache} 作为决策缓存 bean 注册后，{@code PolicyDefaultsConfiguration} 中的
 * NoOp {@link org.dromara.db.auth.policy.PolicyCacheInvalidationHook}（{@code @ConditionalOnMissingBean}）
 * 自动让位——DecisionCache 同时实现该 hook，撤权/用户失效即时清缓存。</p>
 *
 * @author DataGate
 */
@Configuration
public class AuthorizationConfig {

    @Bean
    AuthorizationProperties authorizationProperties(
        @Value("${datagate.authz.env-hard-max-rows:5000}") long envHardMaxRows,
        @Value("${datagate.authz.env-hard-max-bytes:52428800}") long envHardMaxBytes,
        @Value("${datagate.authz.env-hard-max-execution-seconds:30}") long envHardMaxExecutionSeconds,
        @Value("${datagate.authz.env-default-max-rows:500}") long envDefaultMaxRows,
        @Value("${datagate.authz.env-default-max-bytes:10485760}") long envDefaultMaxBytes,
        @Value("${datagate.authz.env-default-max-execution-seconds:30}") long envDefaultMaxExecutionSeconds
    ) {
        return new AuthorizationProperties(
            envHardMaxRows, envHardMaxBytes, envHardMaxExecutionSeconds,
            envDefaultMaxRows, envDefaultMaxBytes, envDefaultMaxExecutionSeconds
        );
    }

    /**
     * 决策缓存（TTL 默认 30s，硬上限 60s 强制裁剪，docs/06 §16 撤权生效）。
     * 设为 0 或负数关闭缓存（每次重读授权表，撤权即时但无缓存加速）。
     */
    @Bean
    DecisionCache decisionCache(
        @Value("${datagate.authz.decision-cache-ttl-seconds:30}") long ttlSeconds
    ) {
        return new DecisionCache(ttlSeconds);
    }
}
