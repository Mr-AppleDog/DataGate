package org.dromara.db.workflow.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.db.workflow.domain.bo.EmergencyApplyBo;
import org.dromara.db.workflow.domain.bo.EmergencyApproveBo;
import org.dromara.db.workflow.domain.vo.DbEmergencyAccessVo;

/**
 * 紧急访问服务（docs/03 §10.4、docs/10 M5-04）。
 *
 * <p>双人审批 + 2h 临时授权 + TOTP + 事件编号 + 自动到期 + 即时通知 + 事后 24h 复盘；不续期。
 *
 * @author DataGate
 */
public interface EmergencyAccessService {

    /** 申请人提交紧急访问申请（事件编号+目标+两名审批人+理由；启动双人审批流） */
    Long apply(EmergencyApplyBo bo);

    /** 审批人1/2 办理当前节点（须不同且均非申请人） */
    void approve(EmergencyApproveBo bo);

    /** 拒绝（终止流程） */
    void reject(EmergencyApproveBo bo);

    /** 申请人撤销（仅 PENDING_APPROVAL） */
    void cancel(EmergencyApproveBo bo);

    /** 撤销已激活的紧急授权（即时失效） */
    void revoke(EmergencyApproveBo bo);

    /** 事后复盘（开通后 24h 内必须补充；逾期记录） */
    void postMortem(EmergencyApproveBo bo);

    /** 查询 */
    DbEmergencyAccessVo getById(Long accessId);

    /** 列表（普通用户本人，管理员全部） */
    TableDataInfo<DbEmergencyAccessVo> pageList(PageQuery pageQuery);
}
