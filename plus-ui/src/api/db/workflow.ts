import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 权限申请请求体 */
export interface WorkflowApplyBody {
  approverId: number;
  subjectType: string;
  subjectId: number;
  resourceId: number;
  action: string;
  effect: string;
  expiresAt: string;
  reason: string;
}

/** 权限申请记录 */
export interface WorkflowAppVO {
  id: number;
  applicantId: number;
  approverId: number;
  resourceId: number;
  action: string;
  effect: string;
  status: string;
  grantId: number | null;
  reason: string;
  createTime: string;
}

/** 申请列表查询参数 */
export interface WorkflowQuery {
  pageNum?: number;
  pageSize?: number;
}

/**
 * 提交权限申请
 * @param data 申请请求体
 */
export const apply = (data: WorkflowApplyBody): AxiosPromise<number> => {
  return request({
    url: '/db/workflow/apply',
    method: 'post',
    data
  });
};

/**
 * 审批通过
 * @param applicationId 申请ID
 * @param message 审批意见
 */
export const approve = (applicationId: number, message: string): AxiosPromise<void> => {
  return request({
    url: '/db/workflow/approve',
    method: 'post',
    data: { applicationId, message }
  });
};

/**
 * 驳回申请
 * @param applicationId 申请ID
 * @param message 驳回理由
 */
export const reject = (applicationId: number, message: string): AxiosPromise<void> => {
  return request({
    url: '/db/workflow/reject',
    method: 'post',
    data: { applicationId, message }
  });
};

/**
 * 撤销申请
 * @param applicationId 申请ID
 * @param message 撤销说明
 */
export const cancel = (applicationId: number, message: string): AxiosPromise<void> => {
  return request({
    url: '/db/workflow/cancel',
    method: 'post',
    data: { applicationId, message }
  });
};

/**
 * 查询申请列表
 * @param query 分页参数
 */
export const list = (query: WorkflowQuery): AxiosPromise<WorkflowAppVO[]> => {
  return request({
    url: '/db/workflow/list',
    method: 'get',
    params: query
  });
};

export default { apply, approve, reject, cancel, list };
