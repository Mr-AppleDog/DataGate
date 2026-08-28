package org.dromara.db.observability.metric;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.core.domain.SlowMetricEvent;
import org.dromara.db.core.spi.MetricEventPublisher;
import org.dromara.db.observability.domain.DbSlowBucket;
import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.mapper.DbSlowBucketMapper;
import org.dromara.db.observability.mapper.DbSlowFingerprintMapper;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.domain.DbEnvironment;
import org.dromara.db.resource.mapper.DbDataSourceMapper;
import org.dromara.db.resource.mapper.DbEnvironmentMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 慢查询指标发布器（docs/07 §8：每分钟评估已完成时间桶，允许 2 分钟迟到窗口）。
 *
 * 扫描 FIVE_MIN 桶中 bucket_end ≤ now-2min 且未发布的，解析 dataSource 环境，
 * 构造 {@link SlowMetricEvent} 发布给告警评估（{@link MetricEventPublisher}）。
 * 已发布桶置 published_at 防重复评估。MetricEventPublisher 无实现时跳过（不阻塞采集）。
 *
 * @author DataGate
 */
@Component
public class SlowMetricPublisher {

    private static final long LATE_WINDOW_MS = 2 * 60 * 1000L;

    private final DbSlowBucketMapper bucketMapper;
    private final DbSlowFingerprintMapper fingerprintMapper;
    private final DbDataSourceMapper dataSourceMapper;
    private final DbEnvironmentMapper environmentMapper;
    private final ObjectProvider<MetricEventPublisher> publisherProvider;

    public SlowMetricPublisher(DbSlowBucketMapper bucketMapper,
                                DbSlowFingerprintMapper fingerprintMapper,
                                DbDataSourceMapper dataSourceMapper,
                                DbEnvironmentMapper environmentMapper,
                                ObjectProvider<MetricEventPublisher> publisherProvider) {
        this.bucketMapper = bucketMapper;
        this.fingerprintMapper = fingerprintMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.environmentMapper = environmentMapper;
        this.publisherProvider = publisherProvider;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 45000)
    public void publishCompletedBuckets() {
        MetricEventPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            return;
        }
        Date now = new Date();
        Date cutoff = new Date(now.getTime() - LATE_WINDOW_MS);
        List<DbSlowBucket> buckets = bucketMapper.selectList(new LambdaQueryWrapper<DbSlowBucket>()
            .eq(DbSlowBucket::getGranularity, "FIVE_MIN")
            .le(DbSlowBucket::getBucketEnd, cutoff)
            .isNull(DbSlowBucket::getPublishedAt)
            .orderByAsc(DbSlowBucket::getBucketEnd)
            .last("limit 100"));
        Map<Long, DbSlowFingerprint> fpCache = new HashMap<>();
        Map<Long, String> envCache = new HashMap<>();
        for (DbSlowBucket b : buckets) {
            DbSlowFingerprint fp = fpCache.computeIfAbsent(b.getFingerprintId(), fingerprintMapper::selectById);
            if (fp == null) {
                markPublished(b, now);
                continue;
            }
            String env = envCache.computeIfAbsent(fp.getDataSourceId(), this::resolveEnvironment);
            boolean firstSeen = fp.getFirstSeenAt() != null
                && b.getBucketStart() != null && b.getBucketEnd() != null
                && fp.getFirstSeenAt().getTime() >= b.getBucketStart().getTime()
                && fp.getFirstSeenAt().getTime() <= b.getBucketEnd().getTime();
            SlowMetricEvent metric = new SlowMetricEvent(
                fp.getDataSourceId(),
                null,
                fp.getId(),
                fp.getFingerprint(),
                fp.getNormalizedStatement(),
                fp.getEngine(),
                fp.getDatabaseName(),
                env,
                b.getBucketStart() == null ? 0L : b.getBucketStart().getTime(),
                b.getBucketEnd() == null ? 0L : b.getBucketEnd().getTime(),
                b.getEventCount() == null ? 0 : b.getEventCount(),
                b.getErrorCount() == null ? 0 : b.getErrorCount(),
                b.getTotalDuration() == null ? 0 : b.getTotalDuration(),
                b.getDurationMax() == null ? 0 : b.getDurationMax(),
                b.getDurationP95() == null ? 0 : b.getDurationP95(),
                b.getTotalLockWait(),
                b.getTotalRowsExamined(),
                b.getTotalRowsReturned(),
                firstSeen,
                0,
                false
            );
            try {
                publisher.publish(metric);
            } catch (Exception ignored) {
                // 评估失败不阻塞发布标记（docs/07 §13 通知失败保留 outbox，采集落盘优先）
            }
            markPublished(b, now);
        }
    }

    private void markPublished(DbSlowBucket b, Date now) {
        DbSlowBucket upd = new DbSlowBucket();
        upd.setId(b.getId());
        upd.setPublishedAt(now);
        bucketMapper.updateById(upd);
    }

    private String resolveEnvironment(Long dataSourceId) {
        if (dataSourceId == null) {
            return null;
        }
        DbDataSource ds = dataSourceMapper.selectById(dataSourceId);
        if (ds == null || ds.getEnvironmentId() == null) {
            return null;
        }
        DbEnvironment env = environmentMapper.selectById(ds.getEnvironmentId());
        return env == null ? null : env.getCode();
    }
}
