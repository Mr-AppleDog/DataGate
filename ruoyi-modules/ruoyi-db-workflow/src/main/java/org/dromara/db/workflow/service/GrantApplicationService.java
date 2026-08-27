package org.dromara.db.workflow.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.db.workflow.domain.bo.GrantApplyBo;
import org.dromara.db.workflow.domain.bo.GrantApproveBo;
import org.dromara.db.workflow.domain.vo.GrantApplicationVo;

/**
 * 查询权限申请单服务（M2-02，docs/03 §10.1、docs/10 M2-02）。
 *
 * <p>申请→审批→（批准生成 Grant / 拒绝不生成）。审批流由 WarmFlow 编排，
 * 回调 {@link GrantApprovalCallbackService} 生成授权。</p>
 *
 * @author DataGate
 */
public interface GrantApplicationService {

    /**
     * 申请人提交查询权限申请，启动审批流并办理申请人节点。
     *
     * @return 申请单 ID
     */
    Long apply(GrantApplyBo bo);

    /**
     * 审批人批准（办理审批节点，流程结束触发回调生成 Grant）。
     */
    void approve(GrantApproveBo bo);

    /**
     * 审批人拒绝（不生成授权）。
     */
    void reject(GrantApproveBo bo);

    /**
     * 申请人撤销（不生成授权）。
     */
    void cancel(GrantApproveBo bo);

    /**
     * 申请单列表（普通用户查本人，管理员查全部）。
     */
    TableDataInfo<GrantApplicationVo> pageList(PageQuery pageQuery);
}
