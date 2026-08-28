package org.dromara.db.executor.support;

import org.dromara.db.core.domain.ConnectionContext;
import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.domain.ExecutionPlan;
import org.dromara.db.core.domain.ExecutionResultMeta;
import org.dromara.db.core.domain.ParsedStatement;
import org.dromara.db.core.domain.RowCell;
import org.dromara.db.core.domain.ColumnMeta;
import org.dromara.db.core.domain.RowHeader;
import org.dromara.db.core.enums.ConnectorCapability;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.dromara.db.core.security.SecretValue;
import org.dromara.db.core.spi.ChangeExecutor;
import org.dromara.db.core.spi.DataSourceConnector;
import org.dromara.db.core.spi.MetadataProvider;
import org.dromara.db.core.spi.QueryExecutor;
import org.dromara.db.core.spi.QueryParser;
import org.dromara.db.core.spi.RowCallback;
import org.dromara.db.core.spi.SlowQueryProvider;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 测试用 DataSourceConnector 桩：可配置解析结果与执行结果，记录调用。
 */
public class StubDataSourceConnector implements DataSourceConnector {

    public List<ParsedStatement> cannedParsed;
    public ExecutionResultMeta cannedResult;
    public boolean parseThrows;
    public int emitRows = 1;
    public boolean executeInvoked;
    public String canceledExecutionNo;
    public String lastOriginalStatement;
    public ChangeExecutor cannedChangeExecutor;

    private final StubQueryExecutor executor = new StubQueryExecutor(this);

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(ConnectorCapability.READ_QUERY);
    }

    @Override
    public ConnectionTestResult test(ConnectionProfile profile, SecretValue secret) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MetadataProvider metadataProvider() {
        throw new UnsupportedOperationException();
    }

    @Override
    public QueryParser queryParser() {
        return new QueryParser() {
            @Override
            public List<ParsedStatement> parse(String statement) {
                lastOriginalStatement = statement;
                if (parseThrows) {
                    throw new DbServiceException(DbErrorCode.QUERY_PARSE_FAILED);
                }
                return cannedParsed;
            }

            @Override
            public String parserVersion() {
                return "stub-1.0";
            }
        };
    }

    @Override
    public QueryExecutor queryExecutor() {
        return executor;
    }

    @Override
    public Optional<ChangeExecutor> changeExecutor() {
        return cannedChangeExecutor == null ? Optional.empty() : Optional.of(cannedChangeExecutor);
    }

    @Override
    public Optional<SlowQueryProvider> slowQueryProvider() {
        return Optional.empty();
    }

    static class StubQueryExecutor implements QueryExecutor {

        private final StubDataSourceConnector owner;

        StubQueryExecutor(StubDataSourceConnector o) {
            this.owner = o;
        }

        @Override
        public ExecutionResultMeta execute(ExecutionPlan plan, ConnectionContext ctx, RowCallback callback) {
            owner.executeInvoked = true;
            callback.onHeader(new RowHeader(List.of(new ColumnMeta("c", "VARCHAR", "text"))));
            for (int i = 0; i < owner.emitRows; i++) {
                callback.onRow(List.of(new RowCell("v" + i, false, null)));
            }
            callback.onComplete();
            return owner.cannedResult;
        }

        @Override
        public void cancel(String executionNo) {
            owner.canceledExecutionNo = executionNo;
        }
    }
}
