package org.dromara.db.observability.service;

import org.dromara.db.observability.domain.DbSlowSource;

import java.util.List;

/**
 * 慢查询采集编排服务（docs/07 §4.1 单源锁、先落原始再提交游标、§13 单源失败隔离）。
 *
 * @author DataGate
 */
public interface ISlowQueryCollectionService {

    /**
     * 采集单个来源（幂等：单源锁防集群重复拉取，游标乐观锁防并发覆盖）。
     */
    CollectResult collectOne(Long slowSourceId);

    /**
     * 采集全部 ACTIVE 来源（单源失败不阻塞其他源）。
     *
     * @return 成功采集的来源数
     */
    int collectAll();

    /**
     * 列出采集来源及状态（供 API/看板）。
     */
    List<DbSlowSource> listCollectors();

    /**
     * @param status         OK/SKIPPED/FAILED
     * @param accepted       新增样例数
     * @param duplicate       重复跳过数
     * @param cursorUpdated  游标是否更新
     * @param cursorConflict 游标乐观锁冲突
     * @param errorCode      失败时的平台错误码
     * @param errorSummary   失败摘要（秘密遮蔽后）
     */
    record CollectResult(String status, int accepted, int duplicate,
                          boolean cursorUpdated, boolean cursorConflict,
                          String errorCode, String errorSummary) {
        public static CollectResult ok(int accepted, int duplicate, boolean cu, boolean cc) {
            return new CollectResult("OK", accepted, duplicate, cu, cc, null, null);
        }
        public static CollectResult skipped(String reason) {
            return new CollectResult("SKIPPED", 0, 0, false, false, null, reason);
        }
        public static CollectResult failed(String code, String summary) {
            return new CollectResult("FAILED", 0, 0, false, false, code, summary);
        }
    }
}
