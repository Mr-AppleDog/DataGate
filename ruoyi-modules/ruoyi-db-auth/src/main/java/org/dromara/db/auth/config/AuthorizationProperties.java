package org.dromara.db.auth.config;

/**
 * 鉴权环境限制配置（docs/03 第 7.3 节、docs/10 M2-04）。
 *
 * <p>环境硬上限不可被授权突破；环境默认值用于授权未提供该维度限制时。
 * 生产硬上限：5,000 行 / 50 MB / 30 秒；软默认：500 行 / 10 MB / 30 秒。
 * 全部可通过 application.yml 覆盖。</p>
 *
 * @param envHardMaxRows             环境硬上限行数
 * @param envHardMaxBytes            环境硬上限字节数
 * @param envHardMaxExecutionSeconds 环境硬上限执行秒数
 * @param envDefaultMaxRows          环境默认行数（授权未指定时）
 * @param envDefaultMaxBytes         环境默认字节数
 * @param envDefaultMaxExecutionSeconds 环境默认执行秒数
 * @author DataGate
 */
public record AuthorizationProperties(
    long envHardMaxRows,
    long envHardMaxBytes,
    long envHardMaxExecutionSeconds,
    long envDefaultMaxRows,
    long envDefaultMaxBytes,
    long envDefaultMaxExecutionSeconds
) {

    /**
     * 生产默认配置（docs/10 M2-04）。
     */
    public static AuthorizationProperties productionDefaults() {
        return new AuthorizationProperties(
            5000L, 52428800L, 30L,
            500L, 10485760L, 30L
        );
    }
}
