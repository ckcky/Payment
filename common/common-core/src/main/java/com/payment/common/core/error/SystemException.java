package com.payment.common.core.error;

/**
 * 系统错误：不可预期的内部失败（如基础设施异常、未分类的运行时异常），
 * 不携带业务语义，统一兜底为 5xx（Engineering Standards §1）。
 */
public class SystemException extends RuntimeException {

    public SystemException(String message, Throwable cause) {
        super(message, cause);
    }

    public SystemException(String message) {
        super(message);
    }
}
