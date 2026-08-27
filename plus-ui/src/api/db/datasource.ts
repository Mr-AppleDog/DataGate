import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 数据源 */
export interface DataSourceVO {
  id: number;
  name: string;
  type: string;
  host: string;
  port: number;
  status: string;
}

/**
 * 查询数据源列表
 */
export const list = (): AxiosPromise<DataSourceVO[]> => {
  return request({
    url: '/db/datasource/list',
    method: 'get'
  });
};

export default { list };
