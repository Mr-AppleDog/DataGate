package org.dromara.db.resource.handler;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.db.core.error.DbErrorCode;
import org.dromara.db.core.error.DbServiceException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * DataGate 业务异常处理器（docs/05 第 3 节）。
 *
 * <p>把 {@link DbServiceException} 转换为稳定错误码响应：
 * envelope code = 数字错误码，HTTP 状态 = 建议状态码；
 * 响应不携带数据库堆栈、JDBC URL、用户名或 SQL 参数。</p>
 *
 * <p>使用最高优先级，确保先于上游 RuntimeException 兜底处理器命中。</p>
 *
 * @author DataGate
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class DbExceptionHandler {

    @ExceptionHandler(DbServiceException.class)
    public ResponseEntity<R<Void>> handleDbServiceException(DbServiceException e) {
        DbErrorCode errorCode = e.getErrorCode();
        // 业务异常不打印堆栈；告警级错误码由告警通道处理
        log.warn("业务异常 code={} msg={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
            .body(R.fail(errorCode.getCode(), e.getMessage()));
    }
}
