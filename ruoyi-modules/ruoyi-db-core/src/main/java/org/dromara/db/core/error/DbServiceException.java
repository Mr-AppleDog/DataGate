package org.dromara.db.core.error;

import org.dromara.db.core.error.DbErrorCode;

/**
 * DataGate 业务异常。对外统一转换为 {@link DbErrorCode}，
 * message 面向用户，不得携带秘密、SQL 参数或数据库堆栈。
 *
 * @author DataGate
 */
public class DbServiceException extends RuntimeException {

    private final DbErrorCode errorCode;

    public DbServiceException(DbErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public DbServiceException(DbErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DbErrorCode getErrorCode() {
        return errorCode;
    }
}
