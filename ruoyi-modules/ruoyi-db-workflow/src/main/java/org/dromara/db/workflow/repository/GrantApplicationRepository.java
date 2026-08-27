package org.dromara.db.workflow.repository;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.mybatis.core.page.PageQuery;
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

    /**
     * 启动流程后回填流程实例 ID。
     */
    boolean updateFlowInstanceId(Long id, Long flowInstanceId);

    /**
     * 拒绝/撤销时更新状态（不生成授权）。
     */
    boolean updateStatus(Long id, String status);

    /**
     * 分页查询（applicantId 为 null 查全部，否则查本人申请）。
     */
    Page<GrantApplication> page(Long applicantId, PageQuery pageQuery);
}
