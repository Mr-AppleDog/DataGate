package org.dromara.db.audit.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.db.audit.domain.AuditEvent;
import org.dromara.db.audit.mapper.AuditEventMapper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.audit.support.AuditHashChain;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.RetentionClass;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 审计写入与校验实现。
 *
 * <p>哈希链串行化：同一分片（UTC 日）内通过 PG 事务级咨询锁串行追加，
 * 单机瓶颈可接受（M4 如需更高吞吐按 docs/08 第 9.3 节扩展分片粒度）。</p>
 *
 * @author DataGate
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AuditServiceImpl implements IAuditService {

    private final AuditEventMapper auditEventMapper;
    private final java.util.Optional<io.micrometer.core.instrument.MeterRegistry> meterRegistry;

    /**
     * 追加审计事件。任何异常向上抛出，调用方（高风险动作）失败关闭。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String append(AuditEventInput input) {
        return doAppend(input);
    }

    /**
     * 独立事务追加（拒绝/失败路径，业务回滚后审计仍留存）
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public String appendIsolated(AuditEventInput input) {
        return doAppend(input);
    }

    private String doAppend(AuditEventInput input) {
        try {
            String eventId = doAppendInternal(input);
            meterRegistry.ifPresent(r -> r.counter("datagate.audit.write", "category", input.category().name()).increment());
            return eventId;
        } catch (RuntimeException e) {
            meterRegistry.ifPresent(r -> r.counter("datagate.audit.write.failed", "category", input.category().name()).increment());
            throw e;
        }
    }

    private String doAppendInternal(AuditEventInput input) {
        // 截断到微秒：与 PostgreSQL timestamptz 精度对齐，保证哈希链校验可重算
        Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String chainKey = AuditHashChain.chainKeyOf(occurredAt);
        // 串行化同分片写入
        auditEventMapper.lockChain(chainKey);
        String previousHash = auditEventMapper.selectLatestHash(chainKey);
        if (previousHash == null) {
            previousHash = AuditHashChain.GENESIS;
        }

        String eventId = UUID.randomUUID().toString();
        String traceId = input.traceId() != null ? input.traceId() : MDC.get("traceId");

        AuditEvent event = new AuditEvent();
        event.setEventId(eventId);
        event.setCategory(input.category().name());
        event.setAction(input.action());
        event.setActorId(input.actorId());
        event.setActorSnapshot(input.actorSnapshot());
        event.setTargetType(input.targetType());
        event.setTargetId(input.targetId());
        event.setTargetSnapshot(input.targetSnapshot());
        event.setResult(input.result().name());
        event.setSourceIp(input.sourceIp());
        event.setUserAgent(input.userAgent());
        event.setTraceId(traceId);
        event.setDetails(input.details());
        event.setOccurredAt(occurredAt);
        event.setRetentionClass(resolveRetention(input.category()).name());
        event.setChainKey(chainKey);
        event.setPreviousHash(previousHash);
        event.setEventHash(AuditHashChain.computeEventHash(
            eventId, event.getCategory(), event.getAction(),
            event.getActorId(), event.getActorSnapshot(),
            event.getTargetType(), event.getTargetId(), event.getTargetSnapshot(),
            event.getResult(), event.getSourceIp(), event.getTraceId(),
            event.getDetails(), occurredAt, previousHash));

        auditEventMapper.insert(event);
        return eventId;
    }

    /**
     * 校验分片哈希链：任一事件被篡改/删除/乱序都会在此暴露
     */
    @Override
    public AuditChainVerification verifyChain(String chainKey) {
        List<AuditEvent> events = auditEventMapper.selectByChainKey(chainKey);
        String expectedPrevious = AuditHashChain.GENESIS;
        for (AuditEvent e : events) {
            if (!expectedPrevious.equals(e.getPreviousHash())) {
                return new AuditChainVerification(chainKey, events.size(), false, e.getId());
            }
            String recomputed = AuditHashChain.computeEventHash(
                e.getEventId(), e.getCategory(), e.getAction(),
                e.getActorId(), e.getActorSnapshot(),
                e.getTargetType(), e.getTargetId(), e.getTargetSnapshot(),
                e.getResult(), e.getSourceIp(), e.getTraceId(),
                e.getDetails(), e.getOccurredAt(), e.getPreviousHash());
            if (!recomputed.equals(e.getEventHash())) {
                return new AuditChainVerification(chainKey, events.size(), false, e.getId());
            }
            expectedPrevious = e.getEventHash();
        }
        return new AuditChainVerification(chainKey, events.size(), true, null);
    }

    /**
     * 校验分片范围（归档完整性复核，M6-03）：迭代 UTC 日分片逐个 verifyChain。
     */
    @Override
    public java.util.List<AuditChainVerification> verifyChainRange(String fromChainKey, String toChainKey) {
        java.util.List<AuditChainVerification> results = new java.util.ArrayList<>();
        for (String chainKey : chainKeysBetween(fromChainKey, toChainKey)) {
            results.add(verifyChain(chainKey));
        }
        return results;
    }

    /**
     * 列出 [from, to] 闭区间内的 UTC 日分片键（yyyyMMdd）。纯函数，可单测。
     */
    static java.util.List<String> chainKeysBetween(String fromChainKey, String toChainKey) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        java.time.LocalDate from = java.time.LocalDate.parse(fromChainKey, fmt);
        java.time.LocalDate to = java.time.LocalDate.parse(toChainKey, fmt);
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (java.time.LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            keys.add(d.format(fmt));
        }
        return keys;
    }

    /**
     * 保留类别映射（docs/00 第 3.8 节）：权限/导出/变更 3 年，其余 1 年
     */
    private RetentionClass resolveRetention(AuditCategory category) {
        return switch (category) {
            case AUTH, EXPORT, CHANGE -> RetentionClass.THREE_YEARS;
            default -> RetentionClass.ONE_YEAR;
        };
    }
}
