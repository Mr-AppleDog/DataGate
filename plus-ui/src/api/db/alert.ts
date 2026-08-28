import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 告警规则 */
export interface AlertRuleVO {
  id: number;
  name: string;
  severity: string;
  metric: string;
  operator: string;
  threshold: number;
  durationSeconds: number;
  status: string;
  version: number;
}

/** 告警事件 */
export interface AlertEventVO {
  id: number;
  ruleId: number;
  dedupKey: string;
  severity: string;
  status: string;
  triggerCount: number;
  currentValue: number;
  threshold: number;
  lastFiredAt: string;
  evidenceSummary: string;
}

/** 通知通道 */
export interface NotificationChannelVO {
  id: number;
  type: string;
  name: string;
  config: string;
  secretReference: string;
  status: string;
}

export const listRules = (): AxiosPromise<AlertRuleVO[]> => request({ url: '/db/alert-rules', method: 'get' });
export const createRule = (data: AlertRuleVO): AxiosPromise<AlertRuleVO> => request({ url: '/db/alert-rules', method: 'post', data });
export const updateRule = (data: AlertRuleVO): AxiosPromise<AlertRuleVO> => request({ url: '/db/alert-rules', method: 'put', data });
export const testRule = (id: number, data: any): AxiosPromise<any> => request({ url: `/db/alert-rules/${id}/test`, method: 'post', data });

export const listEvents = (params?: { status?: string; dataSourceId?: number; limit?: number }): AxiosPromise<AlertEventVO[]> =>
  request({ url: '/db/alert-events', method: 'get', params });
export const acknowledgeEvent = (id: number, version: number): AxiosPromise<AlertEventVO> =>
  request({ url: `/db/alert-events/${id}/acknowledge`, method: 'post', params: { version } });
export const silenceEvent = (id: number, data: { until: number; reason: string; version?: number }): AxiosPromise<AlertEventVO> =>
  request({ url: `/db/alert-events/${id}/silence`, method: 'post', data });

export const listChannels = (): AxiosPromise<NotificationChannelVO[]> => request({ url: '/db/notification-channels', method: 'get' });
export const createChannel = (data: NotificationChannelVO): AxiosPromise<NotificationChannelVO> => request({ url: '/db/notification-channels', method: 'post', data });
export const updateChannel = (data: NotificationChannelVO): AxiosPromise<NotificationChannelVO> => request({ url: '/db/notification-channels', method: 'put', data });
export const testChannel = (id: number): AxiosPromise<any> => request({ url: `/db/notification-channels/${id}/test`, method: 'post' });

export default { listRules, createRule, updateRule, testRule, listEvents, acknowledgeEvent, silenceEvent, listChannels, createChannel, updateChannel, testChannel };
