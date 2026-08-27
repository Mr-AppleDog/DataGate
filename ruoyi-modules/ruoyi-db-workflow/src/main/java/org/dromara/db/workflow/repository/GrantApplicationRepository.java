package org.dromara.db.workflow.repository;

import org.dromara.db.workflow.domain.GrantApplication;

/**
 * 申请单写入端口（M2-02）。选择性更新，不覆盖业务字段。
 *
 * @author DataGate
 */
public interface GrantApplicationRepository {

    GrantApplication findById(Long id);

    Long insert(GrantApplication application);

    /**
     * 回填审批结果（状态 + 生成的 grantId + 审批人），不动申请明细字段。
     */
    boolean updateApprovalResult(Long id, String status, Long grantId, Long approverId);
}
