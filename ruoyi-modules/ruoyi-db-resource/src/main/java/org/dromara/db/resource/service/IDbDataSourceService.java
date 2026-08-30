package org.dromara.db.resource.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.db.core.domain.ConnectionTestResult;
import org.dromara.db.resource.domain.DbDataSource;
import org.dromara.db.resource.domain.bo.DbDataSourceBo;
import org.dromara.db.resource.domain.vo.DbDataSourceVo;

import java.util.List;

/**
 * 数据源管理服务（RES-002/003/004，状态机 docs/05 第 4.1 节）
 *
 * @author DataGate
 */
public interface IDbDataSourceService {

    /**
     * 创建草稿数据源。创建前进行 SSRF/网络白名单校验（docs/08 第 7 节）。
     *
     * @param bo 结构化配置
     * @return 数据源 ID
     */
    Long createDraft(DbDataSourceBo bo);

    /**
     * 乐观锁更新非秘密配置
     */
    boolean updateByBo(DbDataSourceBo bo);

    /**
     * 连接测试：解密专用凭据（内存最短驻留），返回分项能力结果而非底层异常。
     * 成功/失败均写审计（CRED-007、RES-004）。
     *
     * @param id 数据源 ID
     */
    ConnectionTestResult verify(Long id);

    /**
     * 启用（仅 VERIFYING 成功后的 ACTIVE/从 DISABLED 恢复）
     */
    boolean enable(Long id);

    /**
     * 禁用（阻止新执行）
     */
    boolean disable(Long id);

    /**
     * 按 ID 查询（不返回任何秘密）
     */
    DbDataSource queryById(Long id);

    /**
     * 分页查询数据源（不含任何秘密字段）
     */
    TableDataInfo<DbDataSourceVo> queryPageList(DbDataSourceBo bo, PageQuery pageQuery);

    /**
     * 查询可进入受控执行链路的数据源。仅返回 ACTIVE 状态，避免控制台选择到草稿、
     * 验证中、已禁用或错误状态的数据源；资源动作授权仍由执行网关逐资源判定。
     */
    List<DbDataSourceVo> queryAvailableList();

    /**
     * 按 ID 查询视图对象（不含任何秘密字段）
     */
    DbDataSourceVo queryVoById(Long id);
}
