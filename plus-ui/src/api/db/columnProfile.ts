import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 列敏感策略视图 */
export interface ColumnProfileVO {
  resourceId: number;
  dataSourceId: number;
  canonicalPath: string;
  columnName: string;
  sensitivityLevel: string;
  maskingType: string;
  maskingConfig: string;
  classificationSource: string;
  confirmedBy: number;
  confirmedAt: string;
}

/** 查询单列策略 */
export const getColumnProfile = (resourceId: number): AxiosPromise<ColumnProfileVO> => {
  return request({ url: `/db/column-profile/${resourceId}`, method: 'get' });
};

/** 按表列出列策略 */
export const listColumnProfileByTable = (tableResourceId: number): AxiosPromise<ColumnProfileVO[]> => {
  return request({ url: `/db/column-profile/list-by-table/${tableResourceId}`, method: 'get' });
};

/** 人工标注列敏感标签（MANUAL） */
export const setManualLabel = (resourceId: number, data: {
  sensitivityLevel: string;
  maskingType: string;
  maskingConfig?: string;
}): AxiosPromise<void> => {
  return request({ url: `/db/column-profile/${resourceId}`, method: 'put', data });
};

export default { getColumnProfile, listColumnProfileByTable, setManualLabel };
