package org.dromara.db.executor.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.db.audit.service.IAuditService;
import org.dromara.db.core.authz.AccessDecision;
import org.dromara.db.core.authz.AuthorizationDecisionService;
import org.dromara.db.core.authz.DecisionLimits;
import org.dromara.db.core.authz.DecisionRequest;
import org.dromara.db.core.domain.AuditEventInput;
import org.dromara.db.core.domain.ColumnMaskingPolicy;
import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.enums.AuditCategory;
import org.dromara.db.core.enums.AuditResult;
import org.dromara.db.core.enums.CredentialPurpose;
import org.dromara.db.core.enums.DataSourceStatus;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.enums.DbAction;
import org.dromara.db.core.enums.ExecutionStatus;
import org.dromara.db.core.enums.MaskingLevel;
import org.dromara.db.core.enums.TlsMode;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.ColumnMaskingPolicyResolver;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.QueryExecutor;
import org.dromara.db.core.spi.ResourcePathResolver;
import org.dromara.db.core.spi.RowCallback;
import org.dromara.db.executor.domain.QueryExecutionRequest;
import org.dromara.db.executor.service.QueryExecutionGateway;
import org.dromara.db.executor.support.CollectingRowCallback;
import org.dromara.db.resource.domain.DbCredential;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.registry.ConnectorRegistry;
import org.dromara.db.resource.service.ICredentialVaultService;
import org.dromara.db.resource.service.IDbDataSourceService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询执行网关实现（docs/02 §6.6/§8.1、docs/06 §4、docs/03 §7）。
 *
 * <p>编排：解析数据源/凭据 → 方言解析 → 逐资源授权判定 → 生成不可变 ExecutionPlan
 * → 组装 ConnectionContext → 调连接器执行器流式回吐 → 写查询审计。
 * 全程失败关闭：数据源未启用/凭据缺失/解析失败/非只读/多语句/资源不可解析/任一拒绝 → REJECTED + 审计。</p>
 *
 * <p>纵深防御：连接器执行器独立重新解析 originalStatement（非只读/多语句 REJECTED）；
 * 本网关在连接器之上再施 5000 行/50MB 硬上限（CollectingRowCallback）。</p>
 *
 * @author DataGate
 */
@Slf4j
@Service
public class QueryExecutionGatewayImpl implements QueryExecutionGateway {

    /** 平台硬上限（docs/10 M2-04：硬 5000 行/50MB，超时 30s）——纵深防御外层 fence */
    static final long HARD_MAX_ROWS = 5000L;
    static final long HARD_MAX_BYTES = 50L * 1024 * 1024;
    static final long HARD_MAX_SECONDS = 30L;

    /** 环境默认上限（客户端未指定时） */
    static final long DEFAULT_MAX_ROWS = 500L;
    static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    private final IDbDataSourceService dataSourceService;
    private final ICredentialVaultService credentialVaultService;
    private final ConnectorRegistry connectorRegistry;
    private final AuthorizationDecisionService decisionService;
    private final IAuditService auditService;
    private final Optional<ResourcePathResolver> pathResolver;
    private final Optional<ColumnMaskingPolicyResolver> columnPolicyResolver;
    private final org.dromara.db.executor.support.DatagateMetrics metrics;

    /** executionNo → 执行器，供 cancel 路由 */
    private final Map<String, QueryExecutor> activeExecutors = new ConcurrentHashMap<>();

    public QueryExecutionGatewayImpl(IDbDataSourceService dataSourceService,
                                     ICredentialVaultService credentialVaultService,
                                     ConnectorRegistry connectorRegistry,
                                     AuthorizationDecisionService decisionService,
                                     IAuditService auditService,
                                     Optional<ResourcePathResolver> pathResolver,
                                     Optional<ColumnMaskingPolicyResolver> columnPolicyResolver,
                                     org.dromara.db.executor.support.DatagateMetrics metrics) {
        this.dataSourceService = dataSourceService;
        this.credentialVaultService = credentialVaultService;
        this.connectorRegistry = connectorRegistry;
        this.decisionService = decisionService;
        this.auditService = auditService;
        this.pathResolver = pathResolver;
        this.columnPolicyResolver = columnPolicyResolver;
        this.metrics = metrics;
    }

    @Override
    public ExecutionResultMeta execute(QueryExecutionRequest req, RowCallback clientCallback) {
        Instant startedAt = Instant.now();
        io.micrometer.core.instrument.Timer.Sample timer = metrics.start();
        metrics.queryStarted();

        // 1. 解析数据源并校验状态（docs/02 §8.1 step 2-3）
        DbDataSource ds = dataSourceService.queryById(req.dataSourceId());
        if (ds == null) {
            return reject(req, AuditResult.DENIED, DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE.name(),
                "数据源不存在", null, null, startedAt);
        }
        if (!DataSourceStatus.ACTIVE.name().equals(ds.getStatus())) {
            return reject(req, AuditResult.DENIED, DbErrorCode.RESOURCE_STATE_CONFLICT.name(),
                "数据源未启用", ds, null, startedAt);
        }
        DataSourceType type;
        try {
            type = DataSourceType.valueOf(ds.getType());
        } catch (IllegalArgumentException e) {
            return reject(req, AuditResult.FAILURE, DbErrorCode.RESOURCE_STATE_CONFLICT.name(),
                "未知数据源类型", ds, null, startedAt);
        }
        DataSourceConnector connector = connectorRegistry.get(type)
            .orElseThrow(() -> auditAndThrow(req, ds, DbErrorCode.RESOURCE_CAPABILITY_UNSUPPORTED,
                "该类型连接器未注册", AuditResult.FAILURE, startedAt, null, null));

        String defaultDatabase = req.databaseName() != null && !req.databaseName().isBlank()
            ? req.databaseName() : ds.getDefaultDatabase();

        // 2. 方言解析（docs/02 §8.1 step 4）
        List<ParsedStatement> parsed;
        try {
            parsed = connector.queryParser().parse(req.statement());
        } catch (Exception e) {
            log.debug("语句解析失败关闭", e);
            return reject(req, AuditResult.DENIED, DbErrorCode.QUERY_PARSE_FAILED.name(),
                "语句解析失败", ds, null, startedAt);
        }
        if (parsed.isEmpty()) {
            return reject(req, AuditResult.DENIED, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(),
                "空语句", ds, null, startedAt);
        }
        // 生产控制台默认单条（docs/06 §4）
        if (parsed.size() > 1) {
            return reject(req, AuditResult.DENIED, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(),
                "生产控制台默认单条，多语句已拒绝", ds, null, startedAt);
        }
        ParsedStatement stmt = parsed.get(0);
        // 只读门禁：生产控制台普通查询只允许只读（docs/06 §5.2）；EXPLAIN 视为只读
        if (!stmt.readonly() && stmt.requiredAction() != DbAction.EXPLAIN
            && stmt.requiredAction() != DbAction.METADATA_READ) {
            return reject(req, AuditResult.DENIED, DbErrorCode.QUERY_UNSAFE_STATEMENT.name(),
                "非只读语句已拒绝", ds, stmt.fingerprint(), startedAt);
        }

        // 3. 资源路径解析为可鉴权 ID（docs/02 §8.1 step 4-5）——按引擎补全未限定库/schema/逻辑DB
        List<String> paths = completeDefaultDatabase(stmt.resourcePaths(), defaultDatabase, type, req.schemaName());
        List<Long> resourceIds = resolveResourceIds(req.dataSourceId(), defaultDatabase, paths, ds, stmt, startedAt, req);

        // 4. 逐资源授权判定（docs/03 §7、docs/06 §4 step 7）——任一拒绝即整体拒绝
        AggregatedDecision agg = decideAll(req, resourceIds, stmt.requiredAction(), ds, stmt.fingerprint(), startedAt);
        if (agg.denied) {
            return reject(req, AuditResult.DENIED, DbErrorCode.AUTH_RESOURCE_DENIED.name(),
                agg.reasonCode, ds, stmt.fingerprint(), startedAt);
        }

        // 5. 生成不可变 ExecutionPlan（docs/02 §8.2）
        String planId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(Math.max(agg.maxSeconds, 1L));
        long maxRows = cap(req.clientMaxRows(), agg.maxRows, HARD_MAX_ROWS);
        long maxBytes = cap(req.clientMaxBytes(), agg.maxBytes, HARD_MAX_BYTES);

        // M5-05c：列脱敏上下文（docs/06 §11 列来源、docs/03 §9 COLUMN_UNMASK 临时明文）
        Map<String, ColumnMaskingPolicy> columnPolicies = columnPolicyResolver
            .map(r -> r.resolveByTableColumn(resourceIds)).orElse(Map.of());
        Map<String, MaskingLevel> columnUnmaskLevels = resolveColumnUnmaskLevels(req, columnPolicies);

        ExecutionPlan plan = new ExecutionPlan(
            planId, req.userId(), req.dataSourceId(), defaultDatabase, req.schemaName(),
            sha256(req.statement()), stmt.normalizedStatement(), stmt.statementType(),
            resourceIds, agg.decisionId, maxRows, maxBytes, agg.maxSeconds,
            createdAt, expiresAt, agg.masking(), columnPolicies, columnUnmaskLevels);

        // 6. 解密凭据 + 组装 ConnectionContext（docs/02 §8.1 step 8-9、ADR-008）
        DbCredential cred = credentialVaultService.findActive(req.dataSourceId(), CredentialPurpose.QUERY)
            .orElseThrow(() -> auditAndThrow(req, ds, DbErrorCode.CREDENTIAL_INVALID,
                "未配置 QUERY 凭据", AuditResult.FAILURE, startedAt, stmt.fingerprint(), agg.decisionId));

        ExecutionResultMeta result;
        try (SecretValue secret = credentialVaultService.resolveActiveSecret(cred.getId())) {
            ConnectionProfile profile = new ConnectionProfile(
                ds.getHost(), ds.getPort(), defaultDatabase, cred.getUsername(),
                parseOptions(ds.getConnectionOptions()), TlsMode.valueOf(ds.getTlsMode()),
                null, null);
            ConnectionContext ctx = new ConnectionContext(profile, secret, req.statement());
            CollectingRowCallback wrapper = new CollectingRowCallback(clientCallback, HARD_MAX_ROWS, HARD_MAX_BYTES);
            QueryExecutor executor = connector.queryExecutor();
            try {
                result = executor.execute(plan, ctx, wrapper);
            } catch (Exception e) {
                log.warn("执行器异常", e);
                result = buildMeta(UUID.randomUUID().toString(), ExecutionStatus.FAILED, startedAt,
                    0, 0, false, DbErrorCode.QUERY_ENGINE_UNAVAILABLE.name());
            }
            if (result != null && result.executionNo() != null) {
                activeExecutors.put(result.executionNo(), executor);
            }
            // 若连接器未报告截断但网关外层已截断，覆盖 truncated
            if (wrapper.truncated() && result != null && !result.truncated()) {
                result = withTruncated(result, true);
            }
        } // secret 销毁

        // 7. 写查询审计（docs/02 §8.1 step 14、M2-05）——只记规模/指纹/状态，不记 SQL 参数与结果正文
        auditQuery(req, ds, result, stmt.fingerprint(), agg.decisionId, startedAt);
        recordMetrics(timer, result, null);
        return result;
    }

    @Override
    public void cancel(String executionNo) {
        QueryExecutor exec = activeExecutors.get(executionNo);
        if (exec != null) {
            exec.cancel(executionNo);
        }
    }

    // ============================ 内部 ============================

    private List<Long> resolveResourceIds(Long dataSourceId, String defaultDatabase, List<String> paths,
                                          DbDataSource ds, ParsedStatement stmt, Instant startedAt, QueryExecutionRequest req) {
        if (paths.isEmpty()) {
            return List.of();
        }
        if (pathResolver.isEmpty()) {
            // 资源路径解析器未注入，带资源引用的请求无法鉴权——失败关闭
            throw auditAndThrow(req, ds, DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE,
                "资源路径解析器未注入，无法鉴权", AuditResult.DENIED, startedAt, stmt.fingerprint(), null);
        }
        List<Long> ids = pathResolver.get().resolve(dataSourceId, defaultDatabase, paths);
        if (ids == null || ids.size() < paths.size() || ids.contains(null)) {
            throw auditAndThrow(req, ds, DbErrorCode.AUTH_RESOURCE_UNDISCOVERABLE,
                "资源尚未同步或不在可见资源目录，请在数据源管理执行元数据同步后重试",
                AuditResult.DENIED, startedAt, stmt.fingerprint(), null);
        }
        return ids;
    }

    /**
     * 逐资源判定，限制取最严（docs/03 §7.3）。任一拒绝即整体拒绝。
     */
    private AggregatedDecision decideAll(QueryExecutionRequest req, List<Long> resourceIds,
                                         DbAction action, DbDataSource ds, String fingerprint, Instant startedAt) {
        long maxRows = HARD_MAX_ROWS, maxBytes = HARD_MAX_BYTES, maxSeconds = HARD_MAX_SECONDS;
        MaskingLevel masking = MaskingLevel.UNMASKED;
        long policyVersion = 0L;
        String decisionId = null;
        for (Long rid : resourceIds) {
            DecisionRequest dr = new DecisionRequest(req.userId(), req.sessionId(), req.sourceIp(),
                rid, action, Map.of());
            AccessDecision d = decisionService.decide(dr);
            if (!d.allowed()) {
                auditDeny(req, ds, d.reasonCode(), fingerprint);
                return AggregatedDecision.denied(d.reasonCode());
            }
            if (decisionId == null) {
                decisionId = d.decisionId();
            }
            if (d.limits() != null) {
                maxRows = Math.min(maxRows, d.limits().maxRows());
                maxBytes = Math.min(maxBytes, d.limits().maxBytes());
                maxSeconds = Math.min(maxSeconds, d.limits().maxExecutionSeconds());
                masking = moreRestrictive(masking, d.limits().maskingLevel());
            }
            policyVersion = Math.max(policyVersion, d.policyVersion());
        }
        if (decisionId == null) {
            // 无资源引用（如 SELECT 1）——允许，用环境默认限制
            decisionId = UUID.randomUUID().toString();
            maxRows = DEFAULT_MAX_ROWS;
            maxBytes = DEFAULT_MAX_BYTES;
        }
        return AggregatedDecision.allowed(decisionId, maxRows, maxBytes, maxSeconds, masking, policyVersion);
    }

    /**
     * 解析列明文级别：对每个敏感列判定 COLUMN_UNMASK 临时授权（含 requireRecentReauth 二次认证，docs/03 §6/§10.2）。
     * 允许则该列 UNMASKED；否则保持资源级 MASKED/HIDDEN。判定异常不泄露原值，列保持掩码。
     */
    private Map<String, MaskingLevel> resolveColumnUnmaskLevels(QueryExecutionRequest req,
                                                                Map<String, ColumnMaskingPolicy> columnPolicies) {
        Map<String, MaskingLevel> out = new HashMap<>();
        if (columnPolicies == null || columnPolicies.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, ColumnMaskingPolicy> e : columnPolicies.entrySet()) {
            ColumnMaskingPolicy cp = e.getValue();
            if (cp == null || !cp.isSensitive() || cp.resourceId() == null) {
                continue;
            }
            DecisionRequest dr = new DecisionRequest(req.userId(), req.sessionId(), req.sourceIp(),
                cp.resourceId(), DbAction.COLUMN_UNMASK, Map.of());
            try {
                AccessDecision d = decisionService.decide(dr);
                if (d.allowed()) {
                    out.put(e.getKey(), MaskingLevel.UNMASKED);
                }
            } catch (RuntimeException ex) {
                log.warn("COLUMN_UNMASK 判定异常 columnId={}", cp.resourceId(), ex);
            }
        }
        return out;
    }

    /**
     * 补全未限定库名的资源路径（docs/06 §4：parser 输出 best-effort 路径，编排者按引擎补全为
     * 与资源目录 canonicalPath 一致的形态，供 ResourcePathResolver 精确匹配）。
     *
     * <p>引擎感知补全（跨 lane 集成适配，M3 切片）：</p>
     * <ul>
     *   <li>MySQL：{@code /table/<t>} → {@code /db/<defaultDatabase>/table/<t>}（无 schema 层，db 即 schema）；</li>
     *   <li>PostgreSQL：{@code /table/<t>} → {@code /schema/<defaultSchema>/table/<t>}（PG schema 是 db 下隐式层，
     *       目录 schema/table 不带 db 前缀；defaultSchema 缺省 {@code public}，与执行器 search_path 一致）；</li>
     *   <li>Redis：{@code /kpp/<prefix>} → {@code /rdb/<defaultDatabase>/kpp/<prefix>}（逻辑 DB，集群固定 0）。</li>
     * </ul>
     * 已限定 schema/db 的路径与已带 db 前缀的 Redis 路径原样返回（匹配目录）。
     */
    static List<String> completeDefaultDatabase(List<String> paths, String defaultDatabase,
                                                 DataSourceType type, String defaultSchema) {
        if (paths == null || paths.isEmpty()) {
            return paths == null ? List.of() : paths;
        }
        return switch (type) {
            case POSTGRESQL -> {
                String schema = (defaultSchema != null && !defaultSchema.isBlank()) ? defaultSchema : "public";
                yield paths.stream().map(p -> {
                    if (p != null && p.startsWith("/table/") && !p.startsWith("/db/") && !p.startsWith("/schema/")) {
                        return "/schema/" + schema + p; // /schema/public/table/<t>
                    }
                    return p; // schema/db 限定路径原样匹配目录
                }).toList();
            }
            case REDIS -> {
                String db = (defaultDatabase != null && !defaultDatabase.isBlank()) ? defaultDatabase : "0";
                yield paths.stream().map(p -> {
                    if (p != null && p.startsWith("/kpp/") && !p.startsWith("/rdb/")) {
                        return "/rdb/" + db + p; // /rdb/0/kpp/<prefix>
                    }
                    return p;
                }).toList();
            }
            default -> {
                // MYSQL/TAIR：/table/<t> → /db/<defaultDatabase>/table/<t>
                if (defaultDatabase == null || defaultDatabase.isBlank()) {
                    yield paths;
                }
                String prefix = "/table/";
                yield paths.stream().map(p -> {
                    if (p != null && p.startsWith(prefix) && !p.startsWith("/db/")) {
                        return "/db/" + defaultDatabase + p;
                    }
                    return p;
                }).toList();
            }
        };
    }

    private void auditQuery(QueryExecutionRequest req, DbDataSource ds, ExecutionResultMeta result,
                            String fingerprint, String decisionId, Instant startedAt) {
        if (result == null) {
            return;
        }
        long durationMs = Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        ExecutionStatus st = result.status();
        AuditResult auditResult = st == ExecutionStatus.SUCCEEDED ? AuditResult.SUCCESS
            : st == ExecutionStatus.CANCELED ? AuditResult.FAILURE
            : st == ExecutionStatus.REJECTED ? AuditResult.DENIED : AuditResult.FAILURE;
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("executionNo", result.executionNo());
        details.put("decisionId", decisionId);
        details.put("fingerprint", fingerprint);
        details.put("durationMs", durationMs);
        details.put("rowCount", result.rowCount());
        details.put("resultBytes", result.resultBytes());
        details.put("truncated", result.truncated());
        details.put("status", st.name());
        if (result.errorCode() != null) {
            details.put("errorCode", result.errorCode());
        }
        try {
            auditService.append(new AuditEventInput(
                AuditCategory.QUERY, "QUERY_EXECUTE", req.userId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(req.dataSourceId()),
                Map.of("name", ds.getName(), "type", ds.getType()),
                auditResult, req.sourceIp(), null, null, details));
        } catch (Exception e) {
            log.warn("查询审计写入失败（高风险动作应失败关闭）", e);
            // 审计写入失败：查询已执行，审计缺失——记录 SECURITY 告警，不向用户泄露
        }
    }

    private void auditDeny(QueryExecutionRequest req, DbDataSource ds, String reasonCode, String fingerprint) {
        try {
            auditService.appendIsolated(new AuditEventInput(
                AuditCategory.QUERY, "QUERY_DENY", req.userId(),
                Map.of("username", safeUsername()),
                "DATA_SOURCE", String.valueOf(req.dataSourceId()),
                Map.of("name", ds.getName(), "type", ds.getType()),
                AuditResult.DENIED, req.sourceIp(), null, null,
                Map.of("reasonCode", reasonCode, "fingerprint", fingerprint == null ? "" : fingerprint)));
        } catch (Exception e) {
            log.warn("拒绝审计写入失败", e);
        }
    }

    private void recordMetrics(io.micrometer.core.instrument.Timer.Sample timer, ExecutionResultMeta result, String errorCodeOverride) {
        metrics.queryEnded();
        String status = result == null ? "UNKNOWN" : result.status().name();
        String ec = errorCodeOverride != null ? errorCodeOverride : (result != null && result.errorCode() != null ? result.errorCode() : "");
        metrics.stop(timer, "datagate.query.duration", "status", status, "errorCode", ec);
        if (result != null && result.status() != ExecutionStatus.SUCCEEDED) {
            metrics.increment("datagate.query.failed", "status", status);
        }
    }

    /** 拒绝路径：返回 REJECTED 元数据 + 写拒绝审计 */
    private ExecutionResultMeta reject(QueryExecutionRequest req, AuditResult auditResult,
                                       String errorCode, String reason, DbDataSource ds,
                                       String fingerprint, Instant startedAt) {
        long durationMs = Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        if (ds != null) {
            try {
                auditService.appendIsolated(new AuditEventInput(
                    AuditCategory.QUERY, "QUERY_REJECT", req.userId(),
                    Map.of("username", safeUsername()),
                    "DATA_SOURCE", String.valueOf(req.dataSourceId()),
                    ds.getName() == null ? Map.of() : Map.of("name", ds.getName(), "type", ds.getType()),
                    auditResult, req.sourceIp(), null, null,
                    Map.of("errorCode", errorCode, "reason", reason,
                        "fingerprint", fingerprint == null ? "" : fingerprint,
                        "durationMs", durationMs)));
            } catch (Exception e) {
                log.warn("拒绝审计写入失败", e);
            }
        }
        ExecutionResultMeta m = buildMeta(UUID.randomUUID().toString(), ExecutionStatus.REJECTED, startedAt,
            0, 0, false, errorCode);
        recordMetrics(null, m, errorCode);
        return m;
    }

    /** 审计后抛异常（用于无法返回 REJECTED 而必须中断的路径） */
    private DbServiceException auditAndThrow(QueryExecutionRequest req, DbDataSource ds, DbErrorCode code,
                                             String message, AuditResult auditResult, Instant startedAt,
                                             String fingerprint, String decisionId) {
        reject(req, auditResult, code.name(), message, ds, fingerprint, startedAt);
        metrics.increment("datagate.query.rejected", "errorCode", code.name());
        metrics.queryEnded();
        return new DbServiceException(code, message);
    }

    private static String safeUsername() {
        try {
            String n = LoginHelper.getUsername();
            return n == null ? "" : n;
        } catch (Exception e) {
            return "";
        }
    }

    private static long cap(Long clientVal, long decisionVal, long hard) {
        long v = clientVal != null ? clientVal : decisionVal;
        return Math.min(v, hard);
    }

    private static MaskingLevel moreRestrictive(MaskingLevel a, MaskingLevel b) {
        if (a == MaskingLevel.HIDDEN || b == MaskingLevel.HIDDEN) return MaskingLevel.HIDDEN;
        if (a == MaskingLevel.MASKED || b == MaskingLevel.MASKED) return MaskingLevel.MASKED;
        return MaskingLevel.UNMASKED;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "sha256-err";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            return m.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static ExecutionResultMeta buildMeta(String executionNo, ExecutionStatus status, Instant startedAt,
                                                 long rowCount, long resultBytes, boolean truncated, String errorCode) {
        long durationMs = Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        return new ExecutionResultMeta(executionNo, status, durationMs, rowCount, resultBytes, truncated, errorCode);
    }

    private static ExecutionResultMeta withTruncated(ExecutionResultMeta r, boolean truncated) {
        return new ExecutionResultMeta(r.executionNo(), r.status(), r.durationMs(), r.rowCount(),
            r.resultBytes(), truncated, r.errorCode());
    }

    /** 聚合判定结果 */
    private record AggregatedDecision(boolean denied, String reasonCode, String decisionId,
                                      long maxRows, long maxBytes, long maxSeconds,
                                      MaskingLevel masking, long policyVersion) {
        static AggregatedDecision denied(String reasonCode) {
            return new AggregatedDecision(true, reasonCode, null, 0, 0, 0, null, 0);
        }

        static AggregatedDecision allowed(String decisionId, long maxRows, long maxBytes,
                                          long maxSeconds, MaskingLevel masking, long policyVersion) {
            return new AggregatedDecision(false, null, decisionId, maxRows, maxBytes, maxSeconds, masking, policyVersion);
        }
    }
}
