package com.payment.common.core.error;

/**
 * 业务错误：可解释、对调用方明确（如参数非法、状态机拒绝、幂等冲突、金额不变量破坏）。
 * 与 {@link SystemException}（不可预期内部错误）分离（Engineering Standards §1）。
 */
public class BizException extends RuntimeException {

    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static BizException of(String code, String message) {
        return new BizException(code, message);
    }

    public String getCode() {
        return code;
    }
}
