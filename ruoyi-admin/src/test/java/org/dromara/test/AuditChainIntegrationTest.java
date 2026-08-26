package org.dromara.test;

import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计设施集成测试（AUD-001/004/006，docs/10 M1-05 验收）。
 *
 * <p>依赖真实 PostgreSQL 元数据库（dev 环境）。运行方式：</p>
 * <pre>mvn test -pl ruoyi-admin -DskipTests=false -DtestTags=integration</pre>
 *
 * <p>整个测试在事务中运行并回滚，不在审计表中留下测试数据。</p>
 *
 * @author DataGate
 */
@Tag("integration")
@SpringBootTest
@Transactional
@Rollback
@DisplayName("审计设施（哈希链 + 不可变）集成测试")
public class AuditChainIntegrationTest {

    @Autowired
    private IAuditService auditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("追加写 → 链校验通过；SQL 篡改被触发器拒绝；伪造事件被链校验发现")
    void appendVerifyAndTamperDetection() {
        // 1. 追加两条事件（同一 UTC 日分片）
        String eventId1 = auditService.append(new AuditEventInput(
            AuditCategory.CONFIG, "DATASOURCE_CREATE", 1L, Map.of("username", "admin"),
            "DATA_SOURCE", "1001", Map.of("name", "集成测试源"),
            AuditResult.SUCCESS, "127.0.0.1", "integration-test", "trace-it-1",
            Map.of("detail", "first")));
        String eventId2 = auditService.append(new AuditEventInput(
            AuditCategory.CREDENTIAL, "CREDENTIAL_CREATE", 1L, Map.of("username", "admin"),
            "CREDENTIAL", "2001", Map.of("purpose", "QUERY"),
            AuditResult.SUCCESS, "127.0.0.1", "integration-test", "trace-it-1",
            Map.of("detail", "second")));

        assertNotNull(eventId1);
        assertNotNull(eventId2);

        String chainKey = jdbcTemplate.queryForObject(
            "SELECT chain_key FROM dbg_audit_event WHERE event_id = ?", String.class, eventId1);
        assertNotNull(chainKey);

        // 2. 第二条事件的 previous_hash 必须等于第一条的 event_hash（链式）
        String hash1 = jdbcTemplate.queryForObject(
            "SELECT event_hash FROM dbg_audit_event WHERE event_id = ?", String.class, eventId1);
        String prev2 = jdbcTemplate.queryForObject(
            "SELECT previous_hash FROM dbg_audit_event WHERE event_id = ?", String.class, eventId2);
        assertEquals(hash1, prev2);

        // 3. 完整链校验通过
        IAuditService.AuditChainVerification ok = auditService.verifyChain(chainKey);
        assertTrue(ok.intact(), "链校验应通过");

        // 4. 篡改防护：UPDATE/DELETE 被数据库触发器拒绝（AUD-004）。
        // 触发器报错会中止当前 PG 子事务，用 SAVEPOINT 隔离以便测试继续。
        jdbcTemplate.execute("SAVEPOINT before_tamper_update");
        assertThrows(Exception.class, () -> jdbcTemplate.update(
            "UPDATE dbg_audit_event SET action = 'HACKED' WHERE event_id = ?", eventId1));
        jdbcTemplate.execute("ROLLBACK TO SAVEPOINT before_tamper_update");

        jdbcTemplate.execute("SAVEPOINT before_tamper_delete");
        assertThrows(Exception.class, () -> jdbcTemplate.update(
            "DELETE FROM dbg_audit_event WHERE event_id = ?", eventId1));
        jdbcTemplate.execute("ROLLBACK TO SAVEPOINT before_tamper_delete");

        // 5. 伪造事件（绕过应用的 INSERT，previous_hash 错误）会被链校验发现
        jdbcTemplate.update("""
            INSERT INTO dbg_audit_event
            (id, event_id, category, action, actor_id, result, occurred_at, retention_class, chain_key, previous_hash, event_hash)
            VALUES (?, ?, 'SECURITY', 'FORGED', 99, 'SUCCESS', now(), 'ONE_YEAR', ?, ?, ?)
            """,
            System.currentTimeMillis(), "forged-event-id", chainKey, "0".repeat(64), "1".repeat(64));
        IAuditService.AuditChainVerification broken = auditService.verifyChain(chainKey);
        assertFalse(broken.intact(),
            "伪造事件必须导致链校验失败, total=" + broken.total() + ", brokenAtId=" + broken.brokenAtId());
        assertNotNull(broken.brokenAtId());
    }
}
