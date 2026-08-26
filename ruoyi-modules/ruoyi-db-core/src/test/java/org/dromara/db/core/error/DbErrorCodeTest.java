package org.dromara.db.core.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误码规范测试（docs/05 第 3 节）：数字段唯一、区间正确
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("统一错误码规范")
class DbErrorCodeTest {

    @Test
    @DisplayName("错误码数字全局唯一且落在规划区间")
    void codesAreUniqueAndRanged() {
        Set<Integer> codes = new HashSet<>();
        for (DbErrorCode e : DbErrorCode.values()) {
            assertTrue(codes.add(e.getCode()), "duplicated error code: " + e.getCode());
            int code = e.getCode();
            boolean inPlannedRange = (code >= 41000 && code < 50000) || code == 50000;
            assertTrue(inPlannedRange, "error code out of planned range: " + e.name());
        }
    }

    @Test
    @DisplayName("枚举名前缀与数字段一致（抽样）")
    void prefixMatchesRange() {
        assertEquals(403, DbErrorCode.AUTH_RESOURCE_DENIED.getHttpStatus());
        assertTrue(DbErrorCode.AUTH_RESOURCE_DENIED.getCode() >= 42000
            && DbErrorCode.AUTH_RESOURCE_DENIED.getCode() < 43000);
        assertTrue(DbErrorCode.QUERY_PARSE_FAILED.getCode() >= 45000
            && DbErrorCode.QUERY_PARSE_FAILED.getCode() < 46000);
        assertTrue(DbErrorCode.REDIS_COMMAND_DENIED.getCode() >= 46000
            && DbErrorCode.REDIS_COMMAND_DENIED.getCode() < 47000);
    }
}
