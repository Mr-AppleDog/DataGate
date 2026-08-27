package org.dromara.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 开发冒烟治装 runner（仅 dev profile，生产绝不运行）。
 *
 * <p>启动时把 admin 密码重置为 admin123（RuoYi 种子哈希）并解绑其 TOTP，
 * 使集成冒烟可程序化登录。**仅 @Profile("dev") 激活**；prod profile 不装配本 bean。</p>
 *
 * @author DataGate
 */
@Slf4j
@Profile("dev")
@Component
public class DevSmokeSetupRunner implements ApplicationRunner {

    /** RuoYi V1 种子 admin 密码哈希（= BCrypt of "admin123"） */
    private static final String ADMIN123_HASH =
        "$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2";

    private final JdbcTemplate jdbcTemplate;

    public DevSmokeSetupRunner(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int pwd = jdbcTemplate.update(
                "UPDATE sys_user SET password = ? WHERE user_name = 'admin'", ADMIN123_HASH);
            int totp = jdbcTemplate.update(
                "DELETE FROM dbg_user_totp WHERE user_id = " +
                    "(SELECT user_id FROM sys_user WHERE user_name = 'admin')");
            log.warn("[DEV-SMOKE] admin 密码重置为 admin123 + TOTP 解绑（pwdRows={}, totpRows={}）——仅 dev profile，生产不运行",
                pwd, totp);
        } catch (Exception e) {
            log.warn("[DEV-SMOKE] 重置失败（忽略，冒烟可手动处理）", e);
        }
    }
}
