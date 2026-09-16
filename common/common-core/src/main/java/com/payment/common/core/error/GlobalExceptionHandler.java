package com.payment.common.core.error;

import com.payment.common.core.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器兜底，统一返回 {@link ApiError}（Engineering Standards §1）。
 * 通过 common-core 的自动配置注册到各服务。
 *
 * <p>未知异常兜底必须记录 ERROR 日志（含堆栈与 traceId）：否则 500 只有响应体没有痕迹，
 * 服务端完全无法定位（2026-09-04 排查 /orders 500 时踩坑）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    /**
     * 路径 / 静态资源不存在 → 404。
     *
     * <p>Spring 6.1+ 的 {@link NoResourceFoundException} 语义就是「没有这个资源」，
     * 若不单独处理会被 {@link #handleUnknown} 兜成 500，把「地址打错」误报为服务故障：
     * 浏览器对每个页面都会自动请求 {@code /favicon.ico}，演示页因此每次都刷 500
     * （2026-09-16 排查 /portal 500 时发现）。</p>
     *
     * <p>只记 WARN 不记堆栈：这不是异常，是正常的「未命中」。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("resource not found traceId={} path={}", TraceContext.getTraceId(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCodes.NOT_FOUND, "resource not found", TraceContext.getTraceId(), request.getRequestURI()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiError> handleSystem(SystemException ex, HttpServletRequest request) {
        log.error("system error traceId={} path={}: {}", TraceContext.getTraceId(), request.getRequestURI(),
                ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCodes.INTERNAL_ERROR, ex.getMessage(), TraceContext.getTraceId(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception traceId={} path={}", TraceContext.getTraceId(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCodes.INTERNAL_ERROR, "internal error", TraceContext.getTraceId(), request.getRequestURI()));
    }

    private HttpStatus mapStatus(String code) {
        return switch (code) {
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.CONFLICT, ErrorCodes.DUPLICATE,
                    ErrorCodes.CONCURRENT_UPDATE,
                    ErrorCodes.STATE_TRANSITION_VIOLATION, ErrorCodes.AMOUNT_INVARIANT_VIOLATION,
                    ErrorCodes.ORDER_NOT_PAYABLE,
                    // Feature 028：路由/可用性类拒绝均为 409——「此刻无合适渠道」是状态冲突，
                    // 不是请求格式错误（400 会误导调用方改参数，而正确做法是稍后重试或改配置）。
                    ErrorCodes.NO_AVAILABLE_CHANNEL, ErrorCodes.CHANNEL_UNAVAILABLE,
                    // spec 027：额度超限同为 409——「此刻这笔放不下」是状态冲突，
                    // 400 会误导调用方以为参数写错了（额度是随时间演进的账户状态）。
                    ErrorCodes.LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
