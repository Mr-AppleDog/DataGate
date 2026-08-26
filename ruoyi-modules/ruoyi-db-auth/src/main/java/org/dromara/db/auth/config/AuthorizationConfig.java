package org.dromara.db.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 鉴权环境限制装配（docs/03 第 7.3 节、docs/10 M2-04）。
 *
 * <p>从 application.yml 读取，缺省取生产默认。全部可配置。</p>
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
}
