package org.dromara.db.audit.service;

import org.dromara.db.core.domain.AuditEventInput;

/**
 * 专项审计写入与校验服务（docs/02 第 6.7 节）。
 *
 * <p>只提供追加写与只读校验；不存在任何业务 update/delete 入口（AUD-004）。
 * 写入失败必须向上抛出异常，使高风险业务动作失败关闭（docs/10 M1-05 验收）。</p>
 *
 * @author DataGate
 */
public interface IAuditService {

    /**
     * 追加一条审计事件（同事务内按分片串行化计算哈希链）。
     * 随调用方事务提交/回滚（REQUIRED）——成功路径与业务操作同生共死。
     *
     * @param input 审计输入（禁止包含秘密与查询结果）
     * @return eventId
     */
    String append(AuditEventInput input);

    /**
     * 在独立事务中追加审计事件（REQUIRES_NEW）——用于拒绝/失败路径，
     * 业务事务回滚后审计仍然留存（攻击与失败必须可查）。
     *
     * @param input 审计输入
     * @return eventId
     */
    String appendIsolated(AuditEventInput input);

    /**
     * 校验指定分片（UTC 日，yyyyMMdd）的哈希链完整性。
     *
     * @param chainKey 分片键
     * @return 校验结果
     */
    AuditChainVerification verifyChain(String chainKey);

    /**
     * 哈希链校验结果
     *
     * @param chainKey    分片键
     * @param total       分片内事件总数
     * @param intact      是否完整无篡改
     * @param brokenAtId  首个校验失败的事件 ID（完整时为 null）
     */
    record AuditChainVerification(String chainKey, long total, boolean intact, Long brokenAtId) {
    }
}
