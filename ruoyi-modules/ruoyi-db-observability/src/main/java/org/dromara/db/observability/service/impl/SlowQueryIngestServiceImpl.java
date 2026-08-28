package org.dromara.db.observability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.db.core.domain.CollectorCursor;
import org.dromara.db.core.domain.SlowQueryRecord;
import org.dromara.db.observability.domain.DbSlowBucket;
import org.dromara.db.observability.domain.DbSlowCursor;
import org.dromara.db.observability.domain.DbSlowFingerprint;
import org.dromara.db.observability.domain.DbSlowSample;
import org.dromara.db.observability.domain.DbSlowSource;
import org.dromara.db.observability.mapper.DbSlowBucketMapper;
import org.dromara.db.observability.mapper.DbSlowCursorMapper;
import org.dromara.db.observability.mapper.DbSlowFingerprintMapper;
import org.dromara.db.observability.mapper.DbSlowSampleMapper;
import org.dromara.db.observability.mapper.DbSlowSourceMapper;
import org.dromara.db.observability.normalize.SlowQueryNormalizer;
import org.dromara.db.observability.normalize.SlowQueryNormalizer.NormalizedResult;
import org.dromara.db.observability.service.ISlowQueryIngestService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 慢查询落盘实现。
 *
 * 事务边界：所有记录的指纹/样例/桶在单事务内完成（失败整体回滚，至少一次重试幂等 source_key）；
 * 游标提交在落盘后独立事务，保证"先落原始再提交游标"（docs/07 §4.1）。
 *
 * @author DataGate
 */
@Service
public class SlowQueryIngestServiceImpl implements ISlowQueryIngestService {

    private static final String GRAN_FIVE_MIN = "FIVE_MIN";
    private static final long FIVE_MIN_MS = 5 * 60 * 1000L;

    private final DbSlowSourceMapper slowSourceMapper;
    private final DbSlowFingerprintMapper fingerprintMapper;
    private final DbSlowSampleMapper sampleMapper;
    private final DbSlowBucketMapper bucketMapper;
    private final DbSlowCursorMapper cursorMapper;
    private final SlowQueryNormalizer normalizer;
    private final TransactionTemplate txTemplate;

    public SlowQueryIngestServiceImpl(DbSlowSourceMapper slowSourceMapper,
                                      DbSlowFingerprintMapper fingerprintMapper,
                                      DbSlowSampleMapper sampleMapper,
                                      DbSlowBucketMapper bucketMapper,
                                      DbSlowCursorMapper cursorMapper,
                                      SlowQueryNormalizer normalizer,
                                      PlatformTransactionManager transactionManager) {
        this.slowSourceMapper = slowSourceMapper;
        this.fingerprintMapper = fingerprintMapper;
        this.sampleMapper = sampleMapper;
        this.bucketMapper = bucketMapper;
        this.cursorMapper = cursorMapper;
        this.normalizer = normalizer;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public IngestResult ingest(Long slowSourceId, String partitionKey, List<SlowQueryRecord> records,
                                CollectorCursor nextCursor) {
        if (records == null || records.isEmpty()) {
            return finishCursor(partitionKey, nextCursor, 0, 0);
        }
        DbSlowSource source = slowSourceMapper.selectById(slowSourceId);
        final Long dataSourceId = source != null ? source.getDataSourceId() : null;
        final int[] counts = new int[2];
        txTemplate.executeWithoutResult(status -> {
            int accepted = 0;
            int duplicate = 0;
            Date now = new Date();
            for (SlowQueryRecord r : records) {
                AcceptedResult ar = ingestOne(slowSourceId, dataSourceId, r, now);
                if (ar.duplicate) {
                    duplicate++;
                } else {
                    accepted++;
                }
            }
            counts[0] = accepted;
            counts[1] = duplicate;
        });
        return finishCursor(partitionKey, nextCursor, counts[0], counts[1]);
    }

    private AcceptedResult ingestOne(Long slowSourceId, Long dataSourceId, SlowQueryRecord r, Date now) {
        // 兜底归一化（采集器未填 normalizedStatement 时用 sanitizedSample 归一化）
        String normalized = r.normalizedStatement();
        String fingerprint = r.fingerprint();
        String parserVersion = r.parserVersion();
        String ingestQuality = r.ingestQuality();
        String riskFlags = null;
        String sanitizedSample = r.sanitizedSample();
        if (normalized == null || normalized.isBlank()) {
            NormalizedResult nr = normalizer.normalize(r.engineType(), sanitizedSample);
            normalized = nr.normalizedStatement();
            fingerprint = nr.fingerprint();
            parserVersion = nr.parserVersion();
            if (ingestQuality == null) {
                ingestQuality = nr.ingestQuality();
            }
            riskFlags = nr.riskFlags();
            if (sanitizedSample == null) {
                sanitizedSample = nr.sanitizedSample();
            }
        }
        if (ingestQuality == null) {
            ingestQuality = "COMPLETE";
        }
        Date occurred = toDate(r.occurredAt());
        Date collected = r.collectedAt() == null ? occurred : toDate(r.collectedAt());

        // 1. 指纹 upsert
        DbSlowFingerprint fp = fingerprintMapper.selectOne(new LambdaQueryWrapper<DbSlowFingerprint>()
            .eq(DbSlowFingerprint::getDataSourceId, dataSourceId)
            .eq(DbSlowFingerprint::getDatabaseName, r.databaseName())
            .eq(DbSlowFingerprint::getEngine, r.engineType())
            .eq(DbSlowFingerprint::getFingerprint, fingerprint));
        Long fingerprintId;
        if (fp == null) {
            DbSlowFingerprint insert = new DbSlowFingerprint();
            insert.setDataSourceId(dataSourceId);
            insert.setDatabaseName(r.databaseName());
            insert.setEngine(r.engineType());
            insert.setFingerprint(fingerprint);
            insert.setNativeFingerprint(r.nativeFingerprint());
            insert.setParserVersion(parserVersion);
            insert.setNormalizedStatement(normalized);
            insert.setRiskFlags(riskFlags);
            insert.setGovernanceStatus("DISCOVERED");
            insert.setFirstSeenAt(occurred);
            insert.setLastSeenAt(occurred);
            fingerprintMapper.insert(insert);
            fingerprintId = insert.getId();
        } else {
            fingerprintId = fp.getId();
            fp.setLastSeenAt(occurred);
            if (riskFlags != null) {
                fp.setRiskFlags(riskFlags);
            }
            if (fp.getNativeFingerprint() == null && r.nativeFingerprint() != null) {
                fp.setNativeFingerprint(r.nativeFingerprint());
            }
            fingerprintMapper.updateById(fp);
        }

        // 2. 样例幂等 insert（source_key 去重）
        DbSlowSample exists = sampleMapper.selectOne(new LambdaQueryWrapper<DbSlowSample>()
            .eq(DbSlowSample::getSlowSourceId, slowSourceId)
            .eq(DbSlowSample::getSourceKey, r.sourceKey()));
        if (exists != null) {
            return new AcceptedResult(false);
        }
        DbSlowSample sample = new DbSlowSample();
        sample.setSlowSourceId(slowSourceId);
        sample.setFingerprintId(fingerprintId);
        sample.setSourceKey(r.sourceKey());
        sample.setSourceEventId(r.sourceEventId());
        sample.setOccurredAt(occurred);
        sample.setCollectedAt(collected);
        sample.setDatabaseName(r.databaseName());
        sample.setDurationMicros(r.durationMicros());
        sample.setLockWaitMicros(r.lockWaitMicros());
        sample.setRowsExamined(r.rowsExamined());
        sample.setRowsReturned(r.rowsReturned());
        sample.setAffectedRows(r.affectedRows());
        sample.setCpuMicros(r.cpuMicros());
        sample.setIoBytes(r.ioBytes());
        sample.setTempBytes(r.tempBytes());
        sample.setClientAddress(r.clientAddress());
        sample.setDbUser(r.dbUser());
        sample.setApplicationName(r.applicationName());
        sample.setSanitizedSample(sanitizedSample);
        sample.setRawAccessLevel("MASKED");
        sample.setSampleRate(r.sampleRate());
        sample.setIngestQuality(ingestQuality);
        sample.setCreateTime(new Date());
        sampleMapper.insert(sample);

        // 3. 5 分钟桶累积
        accumulateBucket(fingerprintId, r, occurred);
        return new AcceptedResult(true);
    }

    private void accumulateBucket(Long fingerprintId, SlowQueryRecord r, Date occurred) {
        long bucketStartMs = (occurred.getTime() / FIVE_MIN_MS) * FIVE_MIN_MS;
        Date bucketStart = new Date(bucketStartMs);
        Date bucketEnd = new Date(bucketStartMs + FIVE_MIN_MS);
        long dur = r.durationMicros();

        DbSlowBucket b = bucketMapper.selectOne(new LambdaQueryWrapper<DbSlowBucket>()
            .eq(DbSlowBucket::getFingerprintId, fingerprintId)
            .eq(DbSlowBucket::getGranularity, GRAN_FIVE_MIN)
            .eq(DbSlowBucket::getBucketStart, bucketStart));
        if (b == null) {
            DbSlowBucket insert = new DbSlowBucket();
            insert.setFingerprintId(fingerprintId);
            insert.setGranularity(GRAN_FIVE_MIN);
            insert.setBucketStart(bucketStart);
            insert.setBucketEnd(bucketEnd);
            insert.setEventCount(1);
            insert.setErrorCount(0);
            insert.setDurationMin(dur);
            insert.setDurationMax(dur);
            insert.setDurationAvg(dur);
            insert.setDurationP95(dur);
            insert.setDurationP99(dur);
            insert.setTotalDuration(dur);
            insert.setTotalLockWait(orZero(r.lockWaitMicros()));
            insert.setTotalRowsExamined(orNull(r.rowsExamined()));
            insert.setTotalRowsReturned(orNull(r.rowsReturned()));
            insert.setCompleteness("COMPLETE");
            insert.setFirstSeenAt(occurred);
            insert.setLastSeenAt(occurred);
            bucketMapper.insert(insert);
        } else {
            int newCount = (b.getEventCount() == null ? 0 : b.getEventCount()) + 1;
            long newTotal = (b.getTotalDuration() == null ? 0 : b.getTotalDuration()) + dur;
            long newMin = b.getDurationMin() == null ? dur : Math.min(b.getDurationMin(), dur);
            long newMax = b.getDurationMax() == null ? dur : Math.max(b.getDurationMax(), dur);
            long newAvg = newCount > 0 ? newTotal / newCount : dur;
            b.setEventCount(newCount);
            b.setTotalDuration(newTotal);
            b.setDurationMin(newMin);
            b.setDurationMax(newMax);
            b.setDurationAvg(newAvg);
            b.setDurationP95(newMax);
            b.setDurationP99(newMax);
            if (r.lockWaitMicros() != null) {
                b.setTotalLockWait((b.getTotalLockWait() == null ? 0 : b.getTotalLockWait()) + r.lockWaitMicros());
            }
            if (r.rowsExamined() != null) {
                b.setTotalRowsExamined((b.getTotalRowsExamined() == null ? 0 : b.getTotalRowsExamined()) + r.rowsExamined());
            }
            if (r.rowsReturned() != null) {
                b.setTotalRowsReturned((b.getTotalRowsReturned() == null ? 0 : b.getTotalRowsReturned()) + r.rowsReturned());
            }
            b.setLastSeenAt(occurred);
            bucketMapper.updateById(b);
        }
    }

    private IngestResult finishCursor(String partitionKey, CollectorCursor nextCursor, int accepted, int duplicate) {
        boolean cursorUpdated = false;
        boolean cursorConflict = false;
        if (nextCursor != null && nextCursor.sourceId() != null) {
            Date now = new Date();
            DbSlowCursor c = cursorMapper.selectOne(new LambdaQueryWrapper<DbSlowCursor>()
                .eq(DbSlowCursor::getSlowSourceId, nextCursor.sourceId())
                .eq(DbSlowCursor::getPartitionKey, partitionKey == null ? "default" : partitionKey));
            if (c == null) {
                DbSlowCursor insert = new DbSlowCursor();
                insert.setSlowSourceId(nextCursor.sourceId());
                insert.setPartitionKey(partitionKey == null ? "default" : partitionKey);
                insert.setCursor(nextCursor.cursor());
                insert.setLastRecordTime(toDate(nextCursor.lastRecordTime()));
                insert.setLastSuccessAt(now);
                insert.setConsecutiveFailures(0);
                insert.setVersion(0L);
                insert.setCreateTime(now);
                insert.setUpdateTime(now);
                cursorMapper.insert(insert);
                cursorUpdated = true;
            } else {
                c.setCursor(nextCursor.cursor());
                c.setLastRecordTime(toDate(nextCursor.lastRecordTime()));
                c.setLastSuccessAt(now);
                c.setConsecutiveFailures(0);
                c.setUpdateTime(now);
                int rows = cursorMapper.updateById(c);
                cursorUpdated = rows > 0;
                cursorConflict = rows == 0;
            }
        }
        return new IngestResult(accepted, duplicate, cursorUpdated, cursorConflict);
    }

    private static Date toDate(Instant instant) {
        return instant == null ? new Date() : Date.from(instant);
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private static Long orNull(Long v) {
        return v;
    }

    private record AcceptedResult(boolean duplicate) {
    }
}
