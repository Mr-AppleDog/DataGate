import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 导出工单视图 */
export interface ExportJobVO {
  id: number;
  requestNo: string;
  applicantId: number;
  dataSourceId: number;
  databaseName: string;
  schemaName: string;
  fingerprint: string;
  resourceSnapshot: string;
  limits: string;
  maskingLevel: string;
  status: string;
  rowCount: number;
  resultBytes: number;
  downloadCount: number;
  expiresAt: string;
  deletedAt: string;
  workflowInstanceId: number;
  createTime: string;
  updateTime: string;
}

export interface ExportApplyBo {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  statement: string;
  ownerApproverId: number;
  dbaApproverId: number;
  maxRows?: number;
  maxBytes?: number;
  reason: string;
}

export interface ExportApproveBo {
  jobId: number;
  message?: string;
}

/** 创建导出申请 */
export const applyExport = (data: ExportApplyBo): AxiosPromise<number> => {
  return request({ url: '/db/export/requests', method: 'post', data });
};

/** 查询工单 */
export const getExport = (id: number): AxiosPromise<ExportJobVO> => {
  return request({ url: `/db/export/jobs/${id}`, method: 'get' });
};

/** 工单列表 */
export const listExport = (params?: any): AxiosPromise<any> => {
  return request({ url: '/db/export/jobs', method: 'get', params });
};

/** 审批通过 */
export const approveExport = (id: number, data: ExportApproveBo): AxiosPromise<void> => {
  return request({ url: `/db/export/jobs/${id}:approve`, method: 'post', data });
};

/** 审批拒绝 */
export const rejectExport = (id: number, data: ExportApproveBo): AxiosPromise<void> => {
  return request({ url: `/db/export/jobs/${id}:reject`, method: 'post', data });
};

/** 申请人撤销 */
export const cancelExport = (id: number, data: ExportApproveBo): AxiosPromise<void> => {
  return request({ url: `/db/export/jobs/${id}:cancel`, method: 'post', data });
};

/** 生成一次性下载票据 */
export const downloadTicket = (id: number): AxiosPromise<string> => {
  return request({ url: `/db/export/jobs/${id}:download-ticket`, method: 'post' });
};

/** 下载 URL（凭票据） */
export const downloadUrl = (ticket: string) => `/db/export/downloads/${ticket}`;

export default { applyExport, getExport, listExport, approveExport, rejectExport, cancelExport, downloadTicket, downloadUrl };
