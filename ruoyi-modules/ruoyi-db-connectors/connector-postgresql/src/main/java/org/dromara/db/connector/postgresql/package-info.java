/**
 * PostgreSQL/RDS PG 连接器（Connector SPI 实现，docs/06 §7）。
 *
 * <p>M3 实现：PostgresqlQueryParser（Druid PG 方言 AST，schema/search_path 资源提取、
 * COPY/DO/SELECT INTO/CREATE TABLE AS/锁语句/副作用函数拦截、安全 EXPLAIN 禁止 ANALYZE、
 * 失败关闭）、PostgresqlQueryExecutor（HikariCP、BEGIN READ ONLY、SET LOCAL statement_timeout/
 * lock_timeout/idle_in_transaction_session_timeout、search_path 固定、application_name=executionId、
 * ROLLBACK+RESET ALL、cancel）、PostgresqlMetadataProvider（pg_catalog 库/Schema/表/视图/物化视图/列）、
 * PostgresqlConnector（@Component 装配）。</p>
 */
package org.dromara.db.connector.postgresql;
