import http from 'k6/http';
import { check, sleep, group } from 'k6';

// =============================================================================
// DataGate 负载压测脚本骨架（docs/09 §10 M6-04，k6；等价 JMeter/Gatling）
// 画像：100 在线 / 50 并发查询 / 300 数据源 / 百万慢事件/日 / 24h 稳态 + 故障注入（用户运行）
// 用法：k6 run --vus 50 --duration 10m datagate-load.k6.js
//       k6 run -e BASE_URL=http://127.0.0.1:8080 -e TOKEN=xxx datagate-load.k6.js
// =============================================================================
const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const TOKEN = __ENV.TOKEN || '';

export const options = {
  scenarios: {
    query_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 10 },   // 灰度爬坡
        { duration: '5m', target: 50 },    // 目标并发 50
        { duration: '10m', target: 50 },   // 稳态
        { duration: '1m', target: 0 },     // 排空
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    'http_req_duration{name:query}': ['p(95)<500'],   // 权限判定 p95≤100ms，排队+执行≤500ms（不含目标源）
    'http_req_failed{type:query}': ['rate<0.01'],
  },
};

const authHeaders = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + TOKEN };

export default function () {
  group('query_submit', () => {
    // 提交只读查询（clientExecutionId 唯一防重放）
    const cid = __VU + '-' + Date.now() + '-' + Math.random().toString(36).slice(2);
    const res = http.post(BASE + '/db/console/query',
      JSON.stringify({
        dataSourceId: 1, databaseName: null, schemaName: null,
        statement: 'SELECT id, name FROM users WHERE id <= 100',
        clientExecutionId: cid,
        clientMaxRows: 500,
      }), { headers: authHeaders, tags: { name: 'query', type: 'query' } });
    check(res, { 'query 200': (r) => r.status === 200 || r.status === 403 });
    sleep(1);
  });

  group('export_apply_light', () => {
    // 导出申请（低频，每 VU 偶发）
    if (__ITER % 50 === 0) {
      http.post(BASE + '/db/export/requests',
        JSON.stringify({
          dataSourceId: 1, statement: 'SELECT id, name FROM users WHERE id <= 1000',
          ownerApproverId: 2, dbaApproverId: 3, reason: 'load-test',
        }), { headers: authHeaders, tags: { name: 'export', type: 'export' } });
    }
  });
}
