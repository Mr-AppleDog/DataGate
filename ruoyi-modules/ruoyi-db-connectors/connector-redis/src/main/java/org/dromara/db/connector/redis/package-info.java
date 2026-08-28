/**
 * Redis/Tair 连接器（Connector SPI 实现，docs/06 §8）。
 *
 * <p>M3 实现：RedisQueryParser（RESP 分词/命令白名单/动作分类/Key 前缀资源提取/强制拒绝/
 * 失败关闭，不接受原始文本拼接）、RedisQueryExecutor（结构化命令派发、SCAN 强制前缀+COUNT、
 * 元素/字节上限、纵深再解析、凭据销毁、cancel 幂等）、LettuceRedisCommandRunner（Lettuce
 * sync API 结构化派发、集群 MOVED/ASK 拓扑处理、响应塑形与硬截断）、RedisMetadataProvider
 * （逻辑 DB/keyspace 概要）、RedisConnector（@Component 装配）。</p>
 */
package org.dromara.db.connector.redis;
