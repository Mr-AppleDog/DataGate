# DataGate 负载压测骨架（M6-04，docs/09 §10）

## 负载画像（docs/09 §10 M6-04）
- 1,000 账号数据 / 100 在线 / 50 并发查询
- 300 数据源配置和元数据规模
- 100 万慢事件/日
- 24 小时稳态 + 故障注入（用户自做）

## 脚本
- `datagate-load.k6.js`：k6 负载脚本（查询+导出申请爬坡→稳态→排空，p95/失败率阈值）
  - 运行：`k6 run -e BASE_URL=http://127.0.0.1:8080 -e TOKEN=<sa-token> --vus 50 --duration 10m datagate-load.k6.js`
  - 阈值：查询 p95 < 500ms（不含目标源耗时，排队+执行段）、失败率 < 1%

## 等价工具
- **JMeter**：HTTP 请求采样（POST /db/console/query with clientExecutionId；ThreadGroup 50 爬坡→稳态→排空；监听器 p95/错误率）
- **Gatling**（Scala）：`scenario("query").exec(http("query").post("/db/console/query").body(...)).injectOpen(rampUsers(50).during(10m))`

## 关注指标（接 M6-02 Prometheus）
- `datagate_query_duration_seconds` p95 / `datagate_query_active` / `datagate_query_failed_total`
- HikariCP `hikaricp_connections_active/pending`、JVM heap、PG 连接数
- 慢事件吞吐（M4 observability：百万/日 = ~12/s 平均，峰值更高）

## 故障注入（用户演练，docs/09 §10）
- 节点退出（优雅停机 M6-01b 排空）、网络断开、Redis 丢失（M6-03 valkey-recover）、主备切换
- 故障期间：失败关闭（高风险动作失败）、只读状态页、审计链连续、人工批准恢复

## 调优预留
- 连接池上限（每源 max 5，全局预算）、队列容量、审计写入分片粒度、Redis 缓存 TTL
- 慢事件分区（按 occurred_at RANGE）在百万/日容量下 ALTER TABLE PARTITION BY（M4 已建月分区）
