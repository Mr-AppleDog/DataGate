package org.dromara.db.core.enums;

/**
 * 查询执行状态机（docs/05 第 4.3 节）。
 * 终态不可修改；UNKNOWN 仅用于平台无法确认数据库端结果的异常场景，不得自动改为成功。
 *
 * @author DataGate
 */
public enum ExecutionStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED,
    TIMED_OUT,
    REJECTED,
    UNKNOWN;

    /**
     * 是否为终态（终态不可再迁移）
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED
            || this == TIMED_OUT || this == REJECTED || this == UNKNOWN;
    }
}
