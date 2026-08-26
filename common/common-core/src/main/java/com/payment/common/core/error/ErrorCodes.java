package com.payment.common.core.error;

/**
 * 公共错误码常量。业务错误（{@link BizException}）与系统错误（{@link SystemException}）分离；
 * 各服务可在此基础上扩展自己的错误码（Engineering Standards §1）。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String CONFLICT = "CONFLICT";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String STATE_TRANSITION_VIOLATION = "STATE_TRANSITION_VIOLATION";
    public static final String AMOUNT_INVARIANT_VIOLATION = "AMOUNT_INVARIANT_VIOLATION";
    public static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
