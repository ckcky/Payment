package com.payment.common.core.result;

/**
 * 统一返回体（project-structure：common-core 提供统一返回体/结果码）。
 * 可选使用；错误路径统一走 {@link com.payment.common.core.error.GlobalExceptionHandler} 返回
 * {@link com.payment.common.core.error.ApiError}。
 */
public final class Result<T> {

    private final String code;
    private final String message;
    private final T data;

    private Result(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("OK", "ok", data);
    }

    public static <T> Result<T> of(String code, String message, T data) {
        return new Result<>(code, message, data);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
