package org.dromara.db.connector.redis;

import org.dromara.db.connector.redis.support.RedisCommandRunner;
import org.dromara.db.connector.redis.support.RedisLimits;
import org.dromara.db.connector.redis.support.RedisResponse;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.spi.QueryExecutor;
import org.dromara.db.core.spi.QueryParser;
import org.dromara.db.core.spi.RowCallback;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Redis 查询执行器（REDIS-201 / docs/06 §4 step 10-13、§8.2、§11）。
 *
 * <p>受控流式执行：接收已授权的 {@link ExecutionPlan} 与编排器组装的 {@link ConnectionContext}
 *（凭据 + 原始命令），纵深防御再解析校验（单条只读读命令、白名单），计算行/字节/SCAN COUNT
 * 限制，经 {@link RedisCommandRunner} 以结构化参数派发（不接受原始文本拼接，docs/06 §8.1），
 * 流式吐行并施以平台硬上限，结束后销毁凭据。</p>
 *
 * <p><b>失败关闭 + 纵深防御</b>：计划过期/凭据已销毁→REJECTED；独立重新解析原始命令——
 * 非只读/多命令/非白名单/写命令/脚本命令一律 REJECTED（AGENTS.md §6：先执行再判断权限禁止）。</p>
 *
 * <p><b>命令派发可插拔</b>：默认 {@link LettuceRedisCommandRunner}（Lettuce 结构化 dispatch +
 * 集群 MOVED/ASK 拓扑处理）；测试注入桩以验证流式限制与截断。</p>
 *
 * <p><b>审计</b>：不记 key 明文/值正文，只返回 {@link ExecutionResultMeta}。</p>
 *
 * @author DataGate
 */
public class RedisQueryExecutor implements QueryExecutor {

    /** 单命令默认元素上限（docs/06 §8.2）。 */
    static final long HARD_MAX_ROWS = 5000L;
    static final long HARD_MAX_BYTES = 50L * 1024 * 1024;
    static final long HARD_MAX_SECONDS = 5L;

    private final QueryParser parser;
    private final RedisCommandRunner runner;

    public RedisQueryExecutor(QueryParser parser) {
        this(parser, new LettuceRedisCommandRunner());
    }

    /** 测试注入桩。 */
    RedisQueryExecutor(QueryParser parser, RedisCommandRunner runner) {
        this.parser = parser;
        this.runner = runner;
    }

    @Override
    public ExecutionResultMeta execute(ExecutionPlan plan, ConnectionContext ctx, RowCallback callback) {
        long start = System.nanoTime();
        String executionNo = "redis-" + UUID.randomUUID();

        // 1. 计划校验（失败关闭）
        if (plan == null) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PARSE_FAILED);
        }
        if (plan.isExpired(Instant.now())) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PLAN_EXPIRED);
        }

        // 2. 上下文校验（失败关闭）
        if (ctx == null) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_ENGINE_UNAVAILABLE);
        }
        if (ctx.profile() == null || ctx.secret() == null || ctx.secret().isDestroyed()) {
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_ENGINE_UNAVAILABLE);
        }
        if (ctx.originalStatement() == null || ctx.originalStatement().isBlank()) {
            ctx.secret().destroy();
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PARSE_FAILED);
        }

        // 3. 纵深防御：独立重新解析命令，校验单条只读读命令
        List<ParsedStatement> parsed;
        try {
            parsed = parser.parse(ctx.originalStatement());
        } catch (RuntimeException e) {
            ctx.secret().destroy();
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_PARSE_FAILED);
        }
        if (parsed.size() != 1) {
            ctx.secret().destroy();
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_LIMIT_EXCEEDED);
        }
        ParsedStatement ps = parsed.get(0);
        if (!ps.readonly() || !isAllowedReadonlyAction(ps.requiredAction())) {
            ctx.secret().destroy();
            return meta(executionNo, ExecutionStatus.REJECTED, start, 0, 0, false,
                DbErrorCode.QUERY_UNSAFE_STATEMENT);
        }

        // 4. 计算限制（docs/06 §8.2 元素上限、§11 字节上限）
        long maxRows = Math.min(Math.max(plan.maxRows(), 1), HARD_MAX_ROWS);
        long maxBytes = Math.min(Math.max(plan.maxBytes(), 1), HARD_MAX_BYTES);
        int scanCount = RedisQueryParser.SCAN_COUNT_CAP;
        RedisLimits limits = new RedisLimits(maxRows, maxBytes, scanCount);

        // 5. 解析 verb+args 并派发（结构化参数，docs/06 §8.1）
        List<String> tokens = RedisQueryParser.tokenize(ctx.originalStatement());
        String verb = tokens.get(0).toUpperCase();
        List<String> args = tokens.subList(1, tokens.size());
        // SCAN：强制注入/收束 COUNT 上限（docs/06 §8.2）
        args = enforceScanCount(verb, args, scanCount);

        long rows = 0;
        long bytes = 0;
        boolean truncated = false;
        try {
            RedisResponse resp = runner.run(ctx.profile(), ctx.secret(), verb, args, limits);
            if (resp != null && resp.header() != null) {
                callback.onHeader(resp.header());
            }
            if (resp != null && resp.rows() != null) {
                for (List<RowCell> row : resp.rows()) {
                    if (rows + 1 > maxRows) {
                        truncated = true;
                        break;
                    }
                    long rowBytes = estimateBytes(row);
                    bytes += rowBytes;
                    boolean cont = callback.onRow(row);
                    rows++;
                    if (!cont) {
                        truncated = true;
                        break;
                    }
                    if (bytes > maxBytes) {
                        truncated = true;
                        break;
                    }
                }
            }
            // 派发器自身硬截断也并入
            if (resp != null && resp.truncated()) {
                truncated = true;
            }
            callback.onComplete();
            return meta(executionNo, ExecutionStatus.SUCCEEDED, start, rows, bytes, truncated, null);
        } catch (Exception e) {
            ExecutionStatus st = classifyException(e);
            DbErrorCode code = switch (st) {
                case TIMED_OUT -> DbErrorCode.QUERY_TIMEOUT;
                case CANCELED -> DbErrorCode.QUERY_CANCELED;
                case REJECTED -> DbErrorCode.QUERY_UNSAFE_STATEMENT;
                default -> DbErrorCode.QUERY_ENGINE_UNAVAILABLE;
            };
            boolean handled = callback.onError(e);
            return meta(executionNo, handled ? ExecutionStatus.FAILED : st, start, rows, bytes, truncated,
                handled ? null : code);
        } finally {
            ctx.secret().destroy();
        }
    }

    @Override
    public void cancel(String executionNo) {
        // P0 单节点无跨执行号取消句柄；Lettuce 命令超时由 socketTimeout 兜底。
        // docs/06 §8.2：Redis 单命令默认 5 秒，超时即中断。取消幂等，不抛。
    }

    // ====================== 内部 ======================

    private static boolean isAllowedReadonlyAction(DbAction a) {
        return a == DbAction.REDIS_READ || a == DbAction.REDIS_SCAN;
    }

    /**
     * SCAN 类命令强制收束 COUNT 上限（docs/06 §8.2：平台强制限制 COUNT）。
     * 若用户已提供 COUNT，取 min(用户值, 上限)；否则注入上限。
     */
    private static List<String> enforceScanCount(String verb, List<String> args, int cap) {
        if (!RedisQueryParser.SCAN_COMMANDS_SET.contains(verb)) {
            return args;
        }
        List<String> out = new ArrayList<>(args);
        int countIdx = -1;
        for (int i = 0; i + 1 < out.size(); i++) {
            if ("COUNT".equalsIgnoreCase(out.get(i))) {
                countIdx = i;
                break;
            }
        }
        if (countIdx >= 0) {
            try {
                long userCount = Long.parseLong(out.get(countIdx + 1));
                out.set(countIdx + 1, String.valueOf(Math.min(Math.max(userCount, 1), cap)));
            } catch (NumberFormatException ignored) {
                out.set(countIdx + 1, String.valueOf(cap));
            }
        } else {
            out.add("COUNT");
            out.add(String.valueOf(cap));
        }
        return out;
    }

    private static long estimateBytes(List<RowCell> cells) {
        long sum = 0;
        for (RowCell c : cells) {
            if (c.value() != null) {
                sum += c.value().getBytes(StandardCharsets.UTF_8).length;
            }
            if (c.binarySummary() != null) {
                sum += c.binarySummary().length();
            }
        }
        return sum;
    }

    private static ExecutionStatus classifyException(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return ExecutionStatus.TIMED_OUT;
        }
        if (msg.contains("moved") || msg.contains("ask")) {
            // 集群重定向由 Lettuce 拓扑处理；若透出为异常，归引擎不可用
            return ExecutionStatus.FAILED;
        }
        return ExecutionStatus.FAILED;
    }

    private static ExecutionResultMeta meta(String executionNo, ExecutionStatus status, long startNanos,
                                            long rows, long bytes, boolean truncated, DbErrorCode code) {
        long durationMs = Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
        return new ExecutionResultMeta(executionNo, status, durationMs, rows, bytes, truncated,
            code == null ? null : code.name());
    }
}
