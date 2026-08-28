package org.dromara.db.observability.service;

import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.domain.DbSlowGovernanceLog;
import org.dromara.db.observability.domain.DbSlowSample;

import java.util.List;

/**
 * 慢查询治理服务（docs/07 §10 + docs/05 §2.9/§4.6）。
 * 状态机迁移 + 追加日志（不可覆盖）+ 指派/评论/期限。
 *
 * @author DataGate
 */
public interface ISlowQueryGovernanceService {

    /** 认领：DISCOVERED→CLAIMED（自动指派负责人） */
    DbSlowFingerprint claim(Long fingerprintId, Long assigneeId, Long operatorId);

    /** 状态迁移（携 version 乐观锁，非法迁移抛 WORKFLOW_STATE_CONFLICT） */
    DbSlowFingerprint transition(Long fingerprintId, String toStatus, Integer version, String comment, Long operatorId);

    /** 追加评论（不改变状态） */
    void comment(Long fingerprintId, String text, Long operatorId);

    /** 指派/改派负责人 */
    DbSlowFingerprint assign(Long fingerprintId, Long assigneeId, Long operatorId);

    /** 指纹列表（治理状态/数据源过滤） */
    List<DbSlowFingerprint> listFingerprints(String governanceStatus, Long dataSourceId, int limit);

    /** 详情：指纹 + 近期样例 + 治理日志（权限过滤后的样例） */
    GovernanceDetail getDetail(Long fingerprintId, int sampleLimit);

    /** 治理详情 */
    record GovernanceDetail(DbSlowFingerprint fingerprint, List<DbSlowSample> samples, List<DbSlowGovernanceLog> logs) {
    }
}
