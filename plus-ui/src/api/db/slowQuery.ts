import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 慢查询指纹 */
export interface SlowFingerprintVO {
  id: number;
  dataSourceId: number;
  databaseName: string;
  engine: string;
  fingerprint: string;
  normalizedStatement: string;
  governanceStatus: string;
  assigneeId: number;
  firstSeenAt: string;
  lastSeenAt: string;
  version: number;
}

/** 采集器 */
export interface SlowCollectorVO {
  id: number;
  dataSourceId: number;
  collectType: string;
  status: string;
  lastSuccessAt: string;
  consecutiveFailures: number;
  lastErrorCode: string;
}

/** 指纹列表 */
export const listFingerprints = (params?: {
  governanceStatus?: string;
  dataSourceId?: number;
  limit?: number;
}): AxiosPromise<SlowFingerprintVO[]> => {
  return request({ url: '/db/slow-query-fingerprints', method: 'get', params });
};

/** 指纹详情（含样例与治理日志） */
export const getFingerprint = (id: number, sampleLimit = 20): AxiosPromise<any> => {
  return request({ url: `/db/slow-query-fingerprints/${id}`, method: 'get', params: { sampleLimit } });
};

/** 认领 */
export const claim = (id: number, assigneeId: number): AxiosPromise<SlowFingerprintVO> => {
  return request({ url: `/db/slow-query-fingerprints/${id}/claim`, method: 'post', params: { assigneeId } });
};

/** 状态迁移 */
export const transition = (
  id: number,
  toStatus: string,
  version: number,
  comment?: string
): AxiosPromise<SlowFingerprintVO> => {
  return request({
    url: `/db/slow-query-fingerprints/${id}/transition`,
    method: 'post',
    params: { toStatus, version, comment }
  });
};

/** 追加评论 */
export const addComment = (id: number, text: string): AxiosPromise<void> => {
  return request({ url: `/db/slow-query-fingerprints/${id}/comments`, method: 'post', params: { text } });
};

/** 采集器列表 */
export const listCollectors = (): AxiosPromise<SlowCollectorVO[]> => {
  return request({ url: '/db/slow-collectors', method: 'get' });
};

/** 手动触发采集（幂等） */
export const runCollector = (id: number): AxiosPromise<any> => {
  return request({ url: `/db/slow-collectors/${id}/run`, method: 'post' });
};

export default { listFingerprints, getFingerprint, claim, transition, addComment, listCollectors, runCollector };
