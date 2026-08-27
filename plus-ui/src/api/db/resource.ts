import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 数据库资源 */
export interface ResourceVO {
  id: number;
  resourceType: string;
  physicalName: string;
  canonicalPath: string;
  status: string;
  children?: ResourceVO[];
}

/** 资源查询参数 */
export interface ResourceQuery {
  dataSourceId?: number;
  parentId?: number;
}

/**
 * 查询资源列表（按数据源与父节点）
 * @param query 查询参数
 */
export const list = (query: ResourceQuery): AxiosPromise<ResourceVO[]> => {
  return request({
    url: '/db/resource/list',
    method: 'get',
    params: query
  });
};

export default { list };
