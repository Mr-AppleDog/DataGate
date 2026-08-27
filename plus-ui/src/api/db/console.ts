import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/** 查询列定义（后端返回对象数组；兼容字符串数组） */
export interface QueryColumnVO {
  name: string;
  typeName?: string;
  displayType?: string;
}

/** 单元格值 */
export interface QueryCellValue {
  value: any;
  truncated: boolean;
  binarySummary?: string | null;
}

/** 结果行：后端为单元格数组 [[cell...]]；兼容 [{value:[cell...]}] */
export type QueryRow = QueryCellValue[] | { value: QueryCellValue[] };

/** 查询结果 */
export interface QueryResultVO {
  columns: QueryColumnVO[] | string[];
  rows: QueryRow[];
  executionNo: string;
  status: string;
  rowCount: number;
  resultBytes: number;
  truncated: boolean;
  durationMs: number;
  errorCode?: string | null;
}

/** 执行查询请求体 */
export interface ConsoleQueryBody {
  dataSourceId: number;
  databaseName?: string;
  statement: string;
  maxRows?: number;
}

/**
 * 执行查询
 * @param data 查询请求体
 */
export const query = (data: ConsoleQueryBody): AxiosPromise<QueryResultVO> => {
  return request({
    url: '/db/console/query',
    method: 'post',
    data
  });
};

/**
 * 取消正在执行的查询
 * @param executionNo 执行编号
 */
export const cancel = (executionNo: string | number): AxiosPromise<void> => {
  return request({
    url: '/db/console/cancel/' + executionNo,
    method: 'post'
  });
};

export default { query, cancel };
