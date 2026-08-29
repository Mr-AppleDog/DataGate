package org.dromara.common.satoken.handler;

import cn.dev33.satoken.exception.NotLoginException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.dromara.common.core.domain.R;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sa-Token 认证异常 HTTP 契约测试（RES-006、docs/05 第 3.1 节）。
 */
@Tag("unit")
class SaTokenExceptionHandlerTest {

    @Test
    void unauthenticatedResponseUsesHttp401AndBusiness401() {
        SaTokenExceptionHandler handler = new SaTokenExceptionHandler();
        AtomicInteger httpStatus = new AtomicInteger(HttpServletResponse.SC_OK);

        R<Void> result = handler.handleNotLoginException(
            new NotLoginException("login", NotLoginException.NOT_TOKEN, NotLoginException.NOT_TOKEN_MESSAGE),
            request(), response(httpStatus));

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, httpStatus.get());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, result.getCode());
        assertEquals("认证失败，无法访问系统资源", result.getMsg());
    }

    private static HttpServletRequest request() {
        return proxy(HttpServletRequest.class, (proxy, method, args) -> {
            if ("getRequestURI".equals(method.getName())) {
                return "/db/datasource/list";
            }
            return defaultValue(method);
        });
    }

    private static HttpServletResponse response(AtomicInteger httpStatus) {
        return proxy(HttpServletResponse.class, (proxy, method, args) -> {
            if ("setStatus".equals(method.getName())) {
                httpStatus.set((Integer) args[0]);
            }
            return defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        return null;
    }
}
