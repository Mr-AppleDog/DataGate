package org.dromara.db.audit.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审计归档范围校验单元测试（docs/08 §9.3 / docs/09 §12.3，M6-03）。
 *
 * @author DataGate
 */
@Tag("unit")
class AuditServiceImplChainRangeTest {

    @Test
    void chain_keys_single_day() {
        assertEquals(List.of("20260828"), AuditServiceImpl.chainKeysBetween("20260828", "20260828"));
    }

    @Test
    void chain_keys_multi_day_range() {
        assertEquals(List.of("20260828", "20260829", "20260830"),
            AuditServiceImpl.chainKeysBetween("20260828", "20260830"));
    }

    @Test
    void chain_keys_cross_month() {
        assertEquals(List.of("20260831", "20260901", "20260902"),
            AuditServiceImpl.chainKeysBetween("20260831", "20260902"));
    }

    @Test
    void invalid_format_throws() {
        assertThrows(java.time.format.DateTimeParseException.class, () -> AuditServiceImpl.chainKeysBetween("bad", "20260828"));
    }

    @Test
    void reversed_range_empty() {
        // from > to → 空列表（不抛）
        assertEquals(List.of(), AuditServiceImpl.chainKeysBetween("20260830", "20260828"));
    }
}
