package org.dromara.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.DromaraApplication;
import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.domain.DbSlowGovernanceLog;
import org.dromara.db.observability.mapper.DbSlowFingerprintMapper;
import org.dromara.db.observability.mapper.DbSlowGovernanceLogMapper;
import org.dromara.db.observability.service.ISlowQueryGovernanceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理端到端集成测试（docs/07 §10 + docs/05 §4.6 状态机）。
 * 构造 DISCOVERED 指纹 → claim → transition IN_PROGRESS→PENDING_VERIFY→RESOLVED → comment，
 * 验证状态机迁移 + 追加日志。仅 -DtestTags=integration 触发；需元库（dev profile）。
 *
 * @author DataGate
 */
@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest(classes = DromaraApplication.class)
@DisplayName("治理端到端：状态机迁移 + 追加日志")
class GovernancePipelineIntegrationTest {

    @Autowired
    private ISlowQueryGovernanceService governanceService;

    @Autowired
    private DbSlowFingerprintMapper fingerprintMapper;

    @Autowired
    private DbSlowGovernanceLogMapper logMapper;

    @Test
    @DisplayName("claim→IN_PROGRESS→PENDING_VERIFY→RESOLVED 主线 + 评论追加日志")
    void claimTransitionResolveFlow() {
        DbSlowFingerprint fp = new DbSlowFingerprint();
        fp.setDataSourceId(999L);
        fp.setEngine("MYSQL");
        fp.setFingerprint("fp-gov-e2e");
        fp.setNormalizedStatement("SELECT * FROM t WHERE id = ?");
        fp.setParserVersion("druid-test");
        fp.setGovernanceStatus("DISCOVERED");
        fp.setFirstSeenAt(new Date());
        fp.setLastSeenAt(new Date());
        fingerprintMapper.insert(fp);
        Long fpId = fp.getId();

        try {
            DbSlowFingerprint claimed = governanceService.claim(fpId, 1L, 1L);
            assertEquals("CLAIMED", claimed.getGovernanceStatus());

            DbSlowFingerprint ip = governanceService.transition(fpId, "IN_PROGRESS", claimed.getVersion(), "开始处理", 1L);
            assertEquals("IN_PROGRESS", ip.getGovernanceStatus());

            DbSlowFingerprint pv = governanceService.transition(fpId, "PENDING_VERIFY", ip.getVersion(), "待验证", 1L);
            assertEquals("PENDING_VERIFY", pv.getGovernanceStatus());

            DbSlowFingerprint resolved = governanceService.transition(fpId, "RESOLVED", pv.getVersion(), "已解决", 1L);
            assertEquals("RESOLVED", resolved.getGovernanceStatus());

            governanceService.comment(fpId, "优化已应用索引", 1L);

            List<DbSlowGovernanceLog> logs = logMapper.selectList(new LambdaQueryWrapper<DbSlowGovernanceLog>()
                .eq(DbSlowGovernanceLog::getFingerprintId, fpId)
                .orderByAsc(DbSlowGovernanceLog::getCreateTime));
            assertTrue(logs.size() >= 4, "应至少 4 条治理日志（3 STATUS_CHANGE + 1 COMMENT）");
            assertEquals("STATUS_CHANGE", logs.get(0).getAction());
            assertEquals("COMMENT", logs.get(logs.size() - 1).getAction());
        } finally {
            logMapper.delete(new LambdaQueryWrapper<DbSlowGovernanceLog>().eq(DbSlowGovernanceLog::getFingerprintId, fpId));
            fingerprintMapper.deleteById(fpId);
        }
    }
}
