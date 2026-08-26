package com.payment.common.core.error;

import java.time.Instant;

/**
 * 统一错误响应体（跨服务一致，可解释）。
 */
public final class ApiError {

    private final String code;
    private final String message;
    private final String traceId;
    private final Instant timestamp;
    private final String path;

    private ApiError(String code, String message, String traceId, String path) {
        this.code = code;
        this.message = message;
        this.traceId = traceId;
        this.timestamp = Instant.now();
        this.path = path;
    }

    public static ApiError of(String code, String message, String traceId, String path) {
        return new ApiError(code, message, traceId, path);
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getPath() {
        return path;
    }
}
