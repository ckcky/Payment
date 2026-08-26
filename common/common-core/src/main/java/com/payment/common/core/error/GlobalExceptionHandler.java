package com.payment.common.core.error;

import com.payment.common.core.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器兜底，统一返回 {@link ApiError}（Engineering Standards §1）。
 * 通过 common-core 的自动配置注册到各服务。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> handleBiz(BizException ex, HttpServletRequest request) {
        HttpStatus status = mapStatus(ex.getCode());
        return ResponseEntity.status(status)
                .body(ApiError.of(ex.getCode(), ex.getMessage(), TraceContext.getTraceId(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCodes.INVALID_ARGUMENT, message, TraceContext.getTraceId(), request.getRequestURI()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiError> handleSystem(SystemException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCodes.INTERNAL_ERROR, ex.getMessage(), TraceContext.getTraceId(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCodes.INTERNAL_ERROR, "internal error", TraceContext.getTraceId(), request.getRequestURI()));
    }

    private HttpStatus mapStatus(String code) {
        return switch (code) {
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.CONFLICT, ErrorCodes.DUPLICATE,
                    ErrorCodes.STATE_TRANSITION_VIOLATION, ErrorCodes.AMOUNT_INVARIANT_VIOLATION -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
