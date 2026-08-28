package org.dromara.db.core.spi;

import org.dromara.db.core.domain.ConnectionProfile;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.core.enums.ConnectorCapability;
import org.dromara.db.core.enums.DataSourceType;
import org.dromara.db.core.security.SecretValue;

import java.util.Optional;
import java.util.Set;

/**
 * 数据源连接器 SPI（docs/02 第 7 节）。
 *
 * <p>业务层只能通过本 SPI 调用连接器，禁止在 Controller 拼 SQL 或操作 JDBC。
 * 未在 {@link #capabilities()} 声明的能力在 UI、API、执行器三层均不可用。</p>
 *
 * @author DataGate
 */
public interface DataSourceConnector {

    /**
     * 引擎类型
     */
    DataSourceType type();

    /**
     * 能力声明
     */
    Set<ConnectorCapability> capabilities();

    /**
     * 连接测试。返回分项能力结果，不向上抛底层驱动异常、不回显连接串密码。
     *
     * @param profile 非秘密连接配置
     * @param secret  秘密（密码/Token），实现方使用完毕后由调用方销毁
     */
    ConnectionTestResult test(ConnectionProfile profile, SecretValue secret);

    /**
     * 元数据提供者（库/Schema/表/列/Redis 逻辑 DB）
     */
    MetadataProvider metadataProvider();

    /**
     * 方言解析器（AST，失败关闭）
     */
    QueryParser queryParser();

    /**
     * 查询执行器
     */
    QueryExecutor queryExecutor();

    /**
     * 慢查询采集提供者（可选能力）
     */
    Optional<SlowQueryProvider> slowQueryProvider();

    /**
     * 变更执行器（可选能力，M5-02）：专用变更账号逐语句执行已审批 DML/DDL。
     * 未声明的引擎不可执行变更（UI/API/执行器三层均不可用）。
     */
    default Optional<ChangeExecutor> changeExecutor() {
        return Optional.empty();
    }
}
