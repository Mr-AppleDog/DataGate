package org.dromara.db.auth.repository;

import org.dromara.db.auth.domain.Grant;

import java.time.Instant;

/**
 * 授权写入端口（docs/03 §2.2、docs/04 §4.2）。
 *
 * <p>授权生命周期由审批流（db-workflow，M2-02）经 {@code GrantAdminService} 调用；
 * 选择性更新（只动撤销/版本/审计字段），不覆盖业务字段。
 * 实现用 GrantMapper + LambdaUpdateWrapper，测试可换内存实现。</p>
 *
 * @author DataGate
 */
public interface GrantWriteRepository {

    /**
     * 插入授权，回填主键。
     */
    Long insert(Grant grant);

    /**
     * 按 ID 查询。
     */
    Grant findById(Long id);

    /**
     * 选择性更新撤销标记与策略版本（不动 subject/resource/action/effect/conditions）。
     *
     * @return 是否更新到行
     */
    boolean updateRevoked(Long id, Instant revokedAt, Long policyVersion, Long updateBy);
}
