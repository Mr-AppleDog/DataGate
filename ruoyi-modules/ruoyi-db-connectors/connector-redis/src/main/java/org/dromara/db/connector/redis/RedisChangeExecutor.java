package org.dromara.db.connector.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.dromara.db.core.change.RedisChangeCommandValidator;
import org.dromara.db.core.domain.ChangeExecutionRequest;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.RedisChangeCommand;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.spi.ChangeExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Redis 变更执行器（docs/06 §8.4，M5-03）。
 *
 * <p>白名单 SET/DEL/HSET/HDEL/EXPIRE，逐 key 前缀鉴权（命中工单授权范围），禁止脚本/事务/管理命令。
 * Lettuce 结构化派发（不接受原始文本拼接，防注入）；逐命令结果记录。集群 MOVED/ASK 由 Lettuce 处理。
 * 失败关闭：校验未过/越权 key/命令异常→FAILED，不泄露 key 明文（遮蔽）。</p>
 *
 * @author DataGate
 */
public class RedisChangeExecutor implements ChangeExecutor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisChangeExecutor.class);
    private static final char Q = '"';
    private static final char COMMA = ',';
    private static final char COLON = ':';
    private static final char LBRACE = '{';
    private static final char RBRACE = '}';
    private static final char LBRACKET = '[';
    private static final char RBRACKET = ']';

    @Override
    public ChangeResult execute(ChangeExecutionRequest req, ConnectionContext ctx) {
        long start = System.nanoTime();
        String executionNo = "redis-change-" + UUID.randomUUID();
        if (ctx == null || ctx.secret() == null || ctx.secret().isDestroyed()) {
            return ChangeResult.failed(executionNo, DbErrorCode.CREDENTIAL_INVALID.name(), ms(start));
        }
        RedisChangeCommandValidator.ValidationOutcome outcome =
            RedisChangeCommandValidator.validateAll(req.redisCommands(), req.authorizedPrefixes());
        if (!outcome.valid()) {
            ctx.secret().destroy();
            return ChangeResult.failed(executionNo, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(), ms(start));
        }
        ConnectionProfile p = ctx.profile();
        RedisURI.Builder ub = RedisURI.builder()
            .withHost(p.host())
            .withPort(p.port())
            .withTimeout(Duration.ofSeconds(Math.max(req.maxExecutionSeconds(), 1)));
        String db = p.defaultDatabase();
        if (db != null && !db.isBlank()) {
            try {
                ub.withDatabase(Integer.parseInt(db.trim()));
            } catch (NumberFormatException ignored) {
                // 集群固定 0
            }
        }
        final RedisURI uri = ub.build();
        ctx.secret().useSecret(chars -> uri.setPassword(new String(chars)));

        long affected = 0;
        ExecutionStatus finalStatus = ExecutionStatus.SUCCEEDED;
        String errorCode = null;
        StringBuilder results = new StringBuilder();
        results.append(LBRACKET);
        try (RedisClient client = RedisClient.create(uri);
             StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
            RedisCommands<String, String> sync = conn.sync();
            List<RedisChangeCommand> cmds = outcome.commands();
            for (int i = 0; i < cmds.size(); i++) {
                RedisChangeCommand c = cmds.get(i);
                if (i > 0) {
                    results.append(COMMA);
                }
                appendResult(results, i, c.op(), "SUCCEEDED", "", "");
                try {
                    String reply = dispatch(sync, c);
                    affected++;
                } catch (RuntimeException e) {
                    // 覆盖该条为失败
                    int len = results.length();
                    results.delete(0, len);
                    rebuildResults(results, cmds, i);
                    finalStatus = ExecutionStatus.FAILED;
                    errorCode = DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name();
                    log.warn("Redis 变更命令异常 op={} key遮蔽={}", c.op(), maskKey(c.key()), e);
                    break;
                }
            }
        } catch (Exception e) {
            finalStatus = ExecutionStatus.FAILED;
            errorCode = DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name();
            log.warn("Redis 连接异常 jobId={}", req.jobId(), e);
        } finally {
            ctx.secret().destroy();
        }
        results.append(RBRACKET);
        return new ChangeResult(executionNo, finalStatus, affected, results.toString(), errorCode, ms(start));
    }

    private static String dispatch(RedisCommands<String, String> sync, RedisChangeCommand c) {
        switch (c.op()) {
            case "SET": return String.valueOf(sync.set(c.key(), c.args().get(0)));
            case "DEL": return String.valueOf(sync.del(c.key()));
            case "HSET": return String.valueOf(sync.hset(c.key(), c.args().get(0), c.args().get(1)));
            case "HDEL": return String.valueOf(sync.hdel(c.key(), c.args().get(0)));
            case "EXPIRE": return String.valueOf(sync.expire(c.key(), Long.parseLong(c.args().get(0))));
            default: throw new IllegalStateException("非白名单命令：" + c.op());
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 4) {
            return "****";
        }
        return key.substring(0, 2) + "****" + key.substring(key.length() - 2);
    }

    /** 重建 results 至失败条为止（前 i 条 SUCCEEDED + 第 i 条 FAILED） */
    private static void rebuildResults(StringBuilder sb, List<RedisChangeCommand> cmds, int failIdx) {
        for (int i = 0; i <= failIdx; i++) {
            if (i > 0) {
                sb.append(COMMA);
            }
            RedisChangeCommand c = cmds.get(i);
            appendResult(sb, i, c.op(), i == failIdx ? "FAILED" : "SUCCEEDED", "",
                i == failIdx ? DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name() : "");
        }
    }

    private static void appendResult(StringBuilder sb, int idx, String op, String status, String reply, String errorCode) {
        sb.append(LBRACE);
        kv(sb, "idx", String.valueOf(idx), false);
        sb.append(COMMA);
        kv(sb, "op", op, true);
        sb.append(COMMA);
        kv(sb, "status", status, true);
        sb.append(COMMA);
        kv(sb, "reply", reply, true);
        sb.append(COMMA);
        kv(sb, "errorCode", errorCode, true);
        sb.append(RBRACE);
    }

    private static void kv(StringBuilder sb, String key, String val, boolean quoteVal) {
        sb.append(Q).append(key).append(Q).append(COLON);
        if (quoteVal) {
            sb.append(Q).append(escape(val)).append(Q);
        } else {
            sb.append(val);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == Q) {
                sb.append('\\').append(Q);
            } else if (c == '\\') {
                sb.append('\\').append('\\');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
