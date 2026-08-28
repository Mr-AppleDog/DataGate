package org.dromara.db.workflow.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.db.core.domain.ChangeResult;
import org.dromara.db.workflow.domain.bo.ChangeApplyBo;
import org.dromara.db.workflow.domain.bo.ChangeApproveBo;
import org.dromara.db.workflow.domain.bo.ChangeScheduleBo;
import org.dromara.db.workflow.domain.vo.DbChangeExecutionVo;
import org.dromara.db.workflow.domain.vo.DbChangeOrderVo;

import java.util.List;

/**
 * SQL 变更工单服务（docs/03 §10.3、docs/05 §2.8、docs/06 §13，M5-02）。
 *
 * <p>DML/DDL 版本化快照 + precheck 风险 + 两级审批（业务负责人→DBA）+ 执行窗口 +
 * 专用变更账号执行 + 幂等逐语句结果。SQL 改动回 DRAFT 清空审批结论。
 *
 * @author DataGate
 */
public interface ChangeOrderService {

    /** 创建草稿（DRAFT） */
    Long create(ChangeApplyBo bo);

    /** 预检查：解析 + 影响和风险检查（PRECHECKING→PRECHECKED/PRECHECK_FAILED） */
    void precheck(Long orderId);

    /** 提交审批（PRECHECKED→PENDING_APPROVAL，启动两级审批流） */
    void submit(Long orderId);

    /** 审批人办理当前节点（业务负责人或 DBA）；申请人不能审批本人 */
    void approve(ChangeApproveBo bo);

    /** 拒绝（终止流程） */
    void reject(ChangeApproveBo bo);

    /** 申请人撤销（仅 PENDING_APPROVAL） */
    void cancel(ChangeApproveBo bo);

    /** DBA 设置执行窗口（APPROVED→SCHEDULED） */
    void schedule(ChangeScheduleBo bo);

    /** 执行（SCHEDULED + 窗口到达 + 二次认证后；幂等，重复请求只执行一次） */
    ChangeResult execute(Long orderId);

    /** 查询工单 */
    DbChangeOrderVo getById(Long orderId);

    /** 工单列表 */
    TableDataInfo<DbChangeOrderVo> pageList(PageQuery pageQuery);

    /** 执行尝试历史 */
    List<DbChangeExecutionVo> listExecutions(Long orderId);
}
