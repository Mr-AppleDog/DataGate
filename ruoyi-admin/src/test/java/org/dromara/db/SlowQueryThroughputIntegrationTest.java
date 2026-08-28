package org.dromara.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 慢查询存储吞吐压测（docs/07 §6.1 基线 100 万事件/日）。
 * 批量插入 N 条 dbg_slow_sample，测量写入吞吐，外推百万/日容量可行性。
 * 仅 -DtestTags=integration 触发；直接 JDBC 不启 Spring 上下文。
 *
 * @author DataGate
 */
@Tag("integration")
@DisplayName("慢查询存储吞吐压测")
class SlowQueryThroughputIntegrationTest {

    private static final String URL = System.getProperty("datagate.meta.url", "jdbc:postgresql://192.168.149.128:5432/datagate");
    private static final String USER = System.getProperty("datagate.meta.user", "postgres");
    private static final String PASS = System.getProperty("datagate.meta.pass", "mrlu");
    private static final int BATCH = 50_000;
    private static final long FP_ID = 88001L;

    @Test
    @DisplayName("批量插入 5 万样例：吞吐外推支撑百万/日（~695/分钟）")
    void bulkInsertThroughput() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM dbg_slow_sample WHERE slow_source_id = 997");
                s.execute("DELETE FROM dbg_slow_fingerprint WHERE id = " + FP_ID);
                s.execute("INSERT INTO dbg_slow_fingerprint(id,tenant_id,data_source_id,engine,fingerprint,parser_version,normalized_statement,governance_status,first_seen_at,last_seen_at,create_time,version) "
                    + "VALUES(" + FP_ID + ",'000000',997,'MYSQL','fp-perf','druid-perf','SELECT * FROM t WHERE id = ?','DISCOVERED',now(),now(),now(),0)");
            }
            long now = System.currentTimeMillis();
            String sql = "INSERT INTO dbg_slow_sample(id,tenant_id,slow_source_id,fingerprint_id,source_key,occurred_at,collected_at,duration_micros,ingest_quality,create_time) "
                + "VALUES(?, '000000', 997, " + FP_ID + ", ?, ?, ?, ?, 'COMPLETE', now())";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                long baseId = 88000000000L;
                long t = System.currentTimeMillis();
                for (int i = 0; i < BATCH; i++) {
                    ps.setLong(1, baseId + i);
                    ps.setString(2, "perf-" + i);
                    ps.setTimestamp(3, new java.sql.Timestamp(t));
                    ps.setTimestamp(4, new java.sql.Timestamp(t));
                    ps.setLong(5, 1000L + i);
                    ps.addBatch();
                    if ((i + 1) % 1000 == 0) {
                        ps.executeBatch();
                        c.commit();
                    }
                }
                ps.executeBatch();
                c.commit();
            }
            long elapsed = System.currentTimeMillis() - now;

            // 验证行数
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT count(*) FROM dbg_slow_sample WHERE slow_source_id = 997")) {
                assertTrue(rs.next());
                long count = rs.getLong(1);
                long perSecond = elapsed > 0 ? (count * 1000 / elapsed) : 0;
                long perDay = perSecond * 86400;
                System.out.println("[Throughput] inserted " + count + " rows in " + elapsed + "ms => "
                    + perSecond + " rows/s => ~" + perDay + " rows/day (target 1,000,000/day)");
                assertTrue(count == BATCH, "应插入 " + BATCH + " 行");
                // 百万/日 ≈ 695/分钟 ≈ 12/s；5 万应在数十秒内完成，外推远超百万/日
                assertTrue(perDay >= 1_000_000L, "吞吐外推应支撑百万/日");
            }

            // cleanup
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM dbg_slow_sample WHERE slow_source_id = 997");
                s.execute("DELETE FROM dbg_slow_fingerprint WHERE id = " + FP_ID);
                c.commit();
            }
        }
    }
}
