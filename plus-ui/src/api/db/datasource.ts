import request from '@/utils/request';
import { AxiosPromise } from 'axios';

export type DataSourceId = string | number;

// 大型实例全量目录同步可能超过全局 50 秒超时；保持按钮 loading，避免用户误判失败后重复提交。
const METADATA_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

/** 数据源（RES-002：仅非秘密配置） */
export interface DataSourceVO {
  id: DataSourceId;
  environmentId: DataSourceId;
  type: string;
  name: string;
  host: string;
  port: number;
  defaultDatabase?: string;
  connectionOptions?: string;
  tlsMode: string;
  status: string;
  ownerType?: string;
  ownerId?: DataSourceId;
  lastVerifiedAt?: string;
  lastErrorCode?: string;
  remark?: string;
  version: number;
  createTime?: string;
}

export interface DataSourceQuery extends PageQuery {
  environmentId?: DataSourceId;
  type?: string;
  name?: string;
}

export interface DataSourceForm {
  id?: DataSourceId;
  environmentId?: DataSourceId;
  type?: string;
  name?: string;
  host?: string;
  port?: number;
  defaultDatabase?: string;
  connectionOptions?: Record<string, string>;
  tlsMode?: string;
  ownerType?: string;
  ownerId?: DataSourceId;
  remark?: string;
  version?: number;
}

export interface EnvironmentVO {
  id: DataSourceId;
  code: string;
  name: string;
  riskLevel: string;
  status: string;
  remark?: string;
}

export interface CredentialVO {
  id: DataSourceId;
  dataSourceId: DataSourceId;
  purpose: 'QUERY' | 'CHANGE' | 'MONITOR';
  username: string;
  status: string;
  lastVerifiedAt?: string;
  rotateDueAt?: string;
  createTime?: string;
}

export interface CredentialForm {
  dataSourceId: DataSourceId;
  purpose: 'QUERY' | 'CHANGE' | 'MONITOR' | '';
  username: string;
  password: string;
}

export interface ConnectionTestResult {
  success: boolean;
  serverVersion?: string;
  capabilities: string[];
  latency?: string | number;
  errorCode?: string;
  errorSummary?: string;
}

/** RES-004：临时凭据仅用于本次连接测试，不保存。 */
export interface DataSourceConnectionTestForm {
  type: string;
  host: string;
  port: number;
  defaultDatabase?: string;
  connectionOptions?: Record<string, string>;
  tlsMode: string;
  username: string;
  password: string;
}

export interface MetadataSyncJobVO {
  id: DataSourceId;
  dataSourceId: DataSourceId;
  triggerType: string;
  status: string;
  metadataVersion?: DataSourceId;
  startedAt?: string;
  finishedAt?: string;
  foundCount: number;
  updatedCount: number;
  droppedCount: number;
  errorCode?: string;
  errorSummary?: string;
}

/** 查询数据源分页列表 */
export const list = (params?: DataSourceQuery): AxiosPromise<DataSourceVO[]> => {
  return request({ url: '/db/datasource/list', method: 'get', params });
};

/** 查询控制台可选数据源；服务端仅返回 ACTIVE 状态。 */
export const listAvailable = (): AxiosPromise<DataSourceVO[]> => {
  return request({ url: '/db/datasource/available', method: 'get' });
};

/** 查询数据源详情（不包含凭据秘密） */
export const getInfo = (id: DataSourceId): AxiosPromise<DataSourceVO> => {
  return request({ url: `/db/datasource/${id}`, method: 'get' });
};

/** 创建草稿数据源 */
export const add = (data: DataSourceForm): AxiosPromise<DataSourceId> => {
  return request({ url: '/db/datasource', method: 'post', data });
};

/** 更新非秘密配置 */
export const update = (data: DataSourceForm): AxiosPromise<void> => {
  return request({ url: '/db/datasource', method: 'put', data });
};

/** 使用已托管凭据测试连接 */
export const verify = (id: DataSourceId): AxiosPromise<ConnectionTestResult> => {
  return request({ url: `/db/datasource/${id}/verify`, method: 'post' });
};

/** 使用不落库的临时凭据测试新增/编辑表单中的当前连接配置。 */
export const testConnection = (data: DataSourceConnectionTestForm): AxiosPromise<ConnectionTestResult> => {
  return request({ url: '/db/datasource/test-connection', method: 'post', data });
};

/** 启用已验证或已禁用的数据源 */
export const enable = (id: DataSourceId): AxiosPromise<void> => {
  return request({ url: `/db/datasource/${id}/enable`, method: 'put', timeout: METADATA_SYNC_TIMEOUT_MS });
};

/** 禁用运行中的数据源 */
export const disable = (id: DataSourceId): AxiosPromise<void> => {
  return request({ url: `/db/datasource/${id}/disable`, method: 'put' });
};

/** 手工触发元数据同步 */
export const sync = (id: DataSourceId): AxiosPromise<MetadataSyncJobVO> => {
  return request({ url: `/db/datasource/${id}/sync`, method: 'post', timeout: METADATA_SYNC_TIMEOUT_MS });
};

/** 查询最近十次同步任务 */
export const listSyncJobs = (id: DataSourceId): AxiosPromise<MetadataSyncJobVO[]> => {
  return request({ url: `/db/datasource/${id}/sync-jobs`, method: 'get' });
};

/** 查询可用环境 */
export const listEnvironments = (): AxiosPromise<EnvironmentVO[]> => {
  return request({ url: '/db/environment/list', method: 'get' });
};

/** 查询凭据元信息；响应永远不包含密码、密文或 Nonce */
export const listCredentials = (dataSourceId: DataSourceId): AxiosPromise<CredentialVO[]> => {
  return request({ url: `/db/credential/list/${dataSourceId}`, method: 'get' });
};

/** 新建用途凭据；密码只写一次 */
export const addCredential = (data: CredentialForm): AxiosPromise<DataSourceId> => {
  return request({ url: '/db/credential', method: 'post', data });
};

/** 安全事件处置用：禁用凭据 */
export const disableCredential = (id: DataSourceId): AxiosPromise<void> => {
  return request({ url: `/db/credential/${id}/disable`, method: 'put' });
};

export default { list };
