import request from '@/utils/request';
import { AxiosPromise } from 'axios';

export interface EmergencyAccessVO {
  id: number;
  requestNo: string;
  eventNo: string;
  applicantId: number;
  approver1Id: number;
  approver2Id: number;
  targetResourceId: number;
  targetAction: string;
  reason: string;
  validFrom: string;
  validUntil: string;
  grantId: number;
  status: string;
  postMortemDueAt: string;
  postMortemContent: string;
  postMortemAt: string;
  createTime: string;
}

export interface EmergencyApplyBo {
  eventNo: string;
  targetResourceId: number;
  targetAction: string;
  approver1Id: number;
  approver2Id: number;
  validHours?: number;
  reason: string;
}

export interface EmergencyApproveBo { accessId: number; message?: string; postMortemContent?: string; }

export const applyEmergency = (data: EmergencyApplyBo): AxiosPromise<number> => request({ url: '/db/emergency/access', method: 'post', data });
export const approveEmergency = (id: number, data: EmergencyApproveBo): AxiosPromise<void> => request({ url: `/db/emergency/access/${id}:approve`, method: 'post', data });
export const rejectEmergency = (id: number, data: EmergencyApproveBo): AxiosPromise<void> => request({ url: `/db/emergency/access/${id}:reject`, method: 'post', data });
export const cancelEmergency = (id: number, data: EmergencyApproveBo): AxiosPromise<void> => request({ url: `/db/emergency/access/${id}:cancel`, method: 'post', data });
export const revokeEmergency = (id: number, data: EmergencyApproveBo): AxiosPromise<void> => request({ url: `/db/emergency/access/${id}:revoke`, method: 'post', data });
export const postMortemEmergency = (id: number, data: EmergencyApproveBo): AxiosPromise<void> => request({ url: `/db/emergency/access/${id}:postmortem`, method: 'post', data });
export const getEmergency = (id: number): AxiosPromise<EmergencyAccessVO> => request({ url: `/db/emergency/access/${id}`, method: 'get' });
export const listEmergency = (params?: any): AxiosPromise<any> => request({ url: '/db/emergency/access', method: 'get', params });

export default { applyEmergency, approveEmergency, rejectEmergency, cancelEmergency, revokeEmergency, postMortemEmergency, getEmergency, listEmergency };
