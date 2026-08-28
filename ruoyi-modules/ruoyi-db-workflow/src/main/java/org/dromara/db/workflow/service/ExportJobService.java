package org.dromara.db.workflow.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.db.workflow.domain.bo.ExportApplyBo;
import org.dromara.db.workflow.domain.bo.ExportApproveBo;
import org.dromara.db.workflow.domain.vo.DbExportJobVo;

import java.io.InputStream;

/**
 * 导出工单服务（docs/03 §10.2、docs/05 §2.7、docs/06 §12）。
 *
 * <p>EXPORT 独立权限 + 两级审批（申请人→资源 Owner→DBA）+ 执行前重鉴权 +
 * 一次性下载票据（5min，单次）+ 对象 24h 生命周期 + 下载/过期/删除审计。
 *
 * @author DataGate
 */
public interface ExportJobService {

    /** 申请人提交导出申请：解析+鉴权+锁定SQL密文+启动两级审批流 */
    Long apply(ExportApplyBo bo);

    /** 审批人办理当前审批节点（Owner 或 DBA）；申请人不能审批本人 */
    void approve(ExportApproveBo bo);

    /** 审批人拒绝（终止流程，不执行导出） */
    void reject(ExportApproveBo bo);

    /** 申请人撤销（仅 PENDING_APPROVAL） */
    void cancel(ExportApproveBo bo);

    /** 查询工单 */
    DbExportJobVo getById(Long jobId);

    /** 工单列表（普通用户本人，管理员全部） */
    TableDataInfo<DbExportJobVo> pageList(PageQuery pageQuery);

    /** 生成一次性下载票据（5min，单次）；返回票据明文（仅申请人，SUCCEEDED 后可领） */
    String issueDownloadTicket(Long jobId);

    /** 凭票据下载：校验未过期+未使用 → 解密对象流；下载后计次并失效票据 */
    InputStream openDownload(String ticket);
}
