package org.dromara.db.observability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.observability.domain.DbSlowBucket;
import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.governance.DeterministicAnalyzer;
import org.dromara.db.observability.governance.DeterministicAnalyzer.AnalysisResult;
import org.dromara.db.observability.mapper.DbSlowBucketMapper;
import org.dromara.db.observability.mapper.DbSlowFingerprintMapper;
import org.dromara.db.observability.service.ISlowQueryAnalysisService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 慢查询确定性分析实现（docs/07 §11）。
 * 加载指纹近期 FIVE_MIN 桶聚合指标，调 {@link DeterministicAnalyzer} 产出风险标记与规则化建议。
 *
 * @author DataGate
 */
@Service
public class SlowQueryAnalysisServiceImpl implements ISlowQueryAnalysisService {

    private final DbSlowFingerprintMapper fingerprintMapper;
    private final DbSlowBucketMapper bucketMapper;

    public SlowQueryAnalysisServiceImpl(DbSlowFingerprintMapper fingerprintMapper,
                                         DbSlowBucketMapper bucketMapper) {
        this.fingerprintMapper = fingerprintMapper;
        this.bucketMapper = bucketMapper;
    }

    @Override
    public AnalysisResult analyze(Long fingerprintId) {
        DbSlowFingerprint fp = fingerprintMapper.selectById(fingerprintId);
        if (fp == null) {
            throw new DbServiceException(DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE, "慢查询指纹不存在");
        }
        List<DbSlowBucket> buckets = bucketMapper.selectList(new LambdaQueryWrapper<DbSlowBucket>()
            .eq(DbSlowBucket::getFingerprintId, fingerprintId)
            .eq(DbSlowBucket::getGranularity, "FIVE_MIN")
            .orderByDesc(DbSlowBucket::getBucketStart)
            .last("limit 20"));
        long count = 0, total = 0, p95 = 0, max = 0;
        Long lockWait = null, rowsExamined = null, rowsReturned = null;
        for (DbSlowBucket b : buckets) {
            count += b.getEventCount() == null ? 0 : b.getEventCount();
            total += b.getTotalDuration() == null ? 0 : b.getTotalDuration();
            p95 = Math.max(p95, b.getDurationP95() == null ? 0 : b.getDurationP95());
            max = Math.max(max, b.getDurationMax() == null ? 0 : b.getDurationMax());
            if (b.getTotalLockWait() != null) {
                lockWait = (lockWait == null ? 0 : lockWait) + b.getTotalLockWait();
            }
            if (b.getTotalRowsExamined() != null) {
                rowsExamined = (rowsExamined == null ? 0 : rowsExamined) + b.getTotalRowsExamined();
            }
            if (b.getTotalRowsReturned() != null) {
                rowsReturned = (rowsReturned == null ? 0 : rowsReturned) + b.getTotalRowsReturned();
            }
        }
        // 突增：最近桶总耗时 ≥ 2x 上一桶
        boolean surge = buckets.size() >= 2
            && buckets.get(0).getTotalDuration() != null && buckets.get(1).getTotalDuration() != null
            && buckets.get(1).getTotalDuration() > 0
            && buckets.get(0).getTotalDuration() >= 2 * buckets.get(1).getTotalDuration();
        boolean firstSeen = fp.getFirstSeenAt() != null
            && (System.currentTimeMillis() - fp.getFirstSeenAt().getTime()) < 3_600_000L;
        return DeterministicAnalyzer.analyze(count, total, p95, max, lockWait, rowsExamined,
            rowsReturned, firstSeen, surge, fp.getNormalizedStatement());
    }
}
