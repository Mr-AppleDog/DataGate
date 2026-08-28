package org.dromara.db.executor.service.impl;

import org.dromara.db.core.enums.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QueryExecutionGatewayImpl#completeDefaultDatabase} 引擎感知补全单测（docs/06 §4）。
 *
 * <p>验证 MySQL/PG/Redis 三引擎未限定库/schema/逻辑DB 的路径补全为与资源目录 canonicalPath
 * 一致的形态，供 ResourcePathResolver 精确匹配。纯逻辑，无 DB。</p>
 *
 * @author DataGate
 */
@Tag("unit")
@DisplayName("网关资源路径补全（引擎感知）")
class PathCompletionTest {

    // ====================== MySQL ======================

    @Test
    @DisplayName("MySQL：/table/<t> → /db/<default>/table/<t>")
    void mysqlUnqualifiedTable() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/table/users"), "mydb", DataSourceType.MYSQL, null);
        assertEquals(List.of("/db/mydb/table/users"), out);
    }

    @Test
    @DisplayName("MySQL：已限定 /db/<db>/table/<t> 原样返回")
    void mysqlQualifiedTableUnchanged() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/db/mydb/table/users"), "mydb", DataSourceType.MYSQL, null);
        assertEquals(List.of("/db/mydb/table/users"), out);
    }

    @Test
    @DisplayName("MySQL：列路径 /db/<db>/table/<t>/col/<c> 原样返回")
    void mysqlColumnPathUnchanged() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/db/mydb/table/users/col/name"), "mydb", DataSourceType.MYSQL, null);
        assertEquals(List.of("/db/mydb/table/users/col/name"), out);
    }

    @Test
    @DisplayName("MySQL：defaultDatabase 为空则不补全")
    void mysqlEmptyDefaultNoOp() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/table/users"), "", DataSourceType.MYSQL, null);
        assertEquals(List.of("/table/users"), out);
    }

    // ====================== PostgreSQL ======================

    @Test
    @DisplayName("PG：/table/<t> → /schema/<defaultSchema>/table/<t>（defaultSchema 缺省 public）")
    void pgUnqualifiedTableDefaultSchema() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/table/users"), "testdb", DataSourceType.POSTGRESQL, null);
        assertEquals(List.of("/schema/public/table/users"), out);
    }

    @Test
    @DisplayName("PG：/table/<t> → /schema/<reqSchema>/table/<t>（显式 schema）")
    void pgUnqualifiedTableExplicitSchema() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/table/users"), "testdb", DataSourceType.POSTGRESQL, "app");
        assertEquals(List.of("/schema/app/table/users"), out);
    }

    @Test
    @DisplayName("PG：schema 限定 /schema/<s>/table/<t> 原样返回（匹配目录）")
    void pgSchemaQualifiedUnchanged() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/schema/public/table/users"), "testdb", DataSourceType.POSTGRESQL, "app");
        assertEquals(List.of("/schema/public/table/users"), out);
    }

    @Test
    @DisplayName("PG：db+schema 限定 /db/<db>/schema/<s>/table/<t> 原样返回")
    void pgDbSchemaQualifiedUnchanged() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/db/testdb/schema/public/table/users"), "testdb", DataSourceType.POSTGRESQL, null);
        assertEquals(List.of("/db/testdb/schema/public/table/users"), out);
    }

    // ====================== Redis ======================

    @Test
    @DisplayName("Redis：/kpp/<prefix> → /rdb/<defaultDb>/kpp/<prefix>")
    void redisKppCompletion() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/kpp/user:"), "0", DataSourceType.REDIS, null);
        assertEquals(List.of("/rdb/0/kpp/user:"), out);
    }

    @Test
    @DisplayName("Redis：defaultDb 缺省补 0")
    void redisKppDefaultDb() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/kpp/order:"), "", DataSourceType.REDIS, null);
        assertEquals(List.of("/rdb/0/kpp/order:"), out);
    }

    @Test
    @DisplayName("Redis：已带 /rdb/ 前缀原样返回")
    void redisAlreadyQualifiedUnchanged() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/rdb/0/kpp/user:"), "0", DataSourceType.REDIS, null);
        assertEquals(List.of("/rdb/0/kpp/user:"), out);
    }

    @Test
    @DisplayName("Redis：SCAN 多前缀分别补全")
    void redisMultiplePrefixes() {
        List<String> out = QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of("/kpp/user:", "/kpp/order:"), "0", DataSourceType.REDIS, null);
        assertTrue(out.contains("/rdb/0/kpp/user:"));
        assertTrue(out.contains("/rdb/0/kpp/order:"));
    }

    // ====================== 通用 ======================

    @Test
    @DisplayName("空路径列表原样返回（不抛）")
    void emptyPathsNoOp() {
        assertTrue(QueryExecutionGatewayImpl.completeDefaultDatabase(
            List.of(), "mydb", DataSourceType.MYSQL, null).isEmpty());
    }
}
