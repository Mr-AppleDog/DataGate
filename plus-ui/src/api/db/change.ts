import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 变更工单视图 */
export interface ChangeOrderVO {
  id: number;
  requestNo: string;
  applicantId: number;
  dataSourceId: number;
  databaseName: string;
  schemaName: string;
  changeType: string;
  fingerprint: string;
  resourceSnapshot: string;
  precheckResult: string;
  rollbackPlan: string;
  impactSummary: string;
  executionWindowStart: string;
  executionWindowEnd: string;
  workflowInstanceId: number;
  status: string;
  createTime: string;
  updateTime: string;
}

/** 变更执行尝试视图 */
export interface ChangeExecutionVO {
  id: number;
  orderId: number;
  attemptNo: number;
  executionNode: string;
  credentialId: number;
  startedAt: string;
  finishedAt: string;
  status: string;
  affectedRows: number;
  errorCode: string;
  errorSummary: string;
  statementResults: string;
}

export interface ChangeApplyBo {
  dataSourceId: number;
  databaseName?: string;
  schemaName?: string;
  changeType: string;
  statement: string;
  bizApproverId: number;
  dbaApproverId: number;
  rollbackPlan?: string;
  impactSummary?: string;
}

export interface ChangeApproveBo { orderId: number; message?: string; }
export interface ChangeScheduleBo { orderId: number; executionWindowStart: string; executionWindowEnd: string; }

export const createChange = (data: ChangeApplyBo): AxiosPromise<number> => request({ url: '/db/change/orders', method: 'post', data });
export const precheckChange = (id: number): AxiosPromise<void> => request({ url: `/db/change/orders/${id}:precheck`, method: 'post' });
export const submitChange = (id: number): AxiosPromise<void> => request({ url: `/db/change/orders/${id}:submit`, method: 'post' });
export const approveChange = (id: number, data: ChangeApproveBo): AxiosPromise<void> => request({ url: `/db/change/orders/${id}:approve`, method: 'post', data });
export const rejectChange = (id: number, data: ChangeApproveBo): AxiosPromise<void> => request({ url: `/db/change/orders/${id}:reject`, method: 'post', data });
export const cancelChange = (id: number, data: ChangeApproveBo): AxiosPromise<void> => request({ url: `/db/change/orders/${id}:cancel`, method: 'post', data });
export const scheduleChange = (id: number, data: ChangeScheduleBo): AxiosPromise<void> => request({ url: `/db/change/orders/${id}:schedule`, method: 'post', data });
export const executeChange = (id: number): AxiosPromise<any> => request({ url: `/db/change/orders/${id}:execute`, method: 'post' });
export const getChange = (id: number): AxiosPromise<ChangeOrderVO> => request({ url: `/db/change/orders/${id}`, method: 'get' });
export const listChange = (params?: any): AxiosPromise<any> => request({ url: '/db/change/orders', method: 'get', params });
export const listExecutions = (id: number): AxiosPromise<ChangeExecutionVO[]> => request({ url: `/db/change/orders/${id}/executions`, method: 'get' });

export default { createChange, precheckChange, submitChange, approveChange, rejectChange, cancelChange, scheduleChange, executeChange, getChange, listChange, listExecutions };
