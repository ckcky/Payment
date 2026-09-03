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
    /** 乐观锁版本冲突：业务上可安全重试（读-改-写重放），与不可重试的 CONFLICT 区分。 */
    public static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String STATE_TRANSITION_VIOLATION = "STATE_TRANSITION_VIOLATION";
    /** 对账批次尚有未处理差异却尝试关闭（ADR-0019 关闭门禁）。 */
    public static final String UNRESOLVED_DIFFERENCES = "UNRESOLVED_DIFFERENCES";
    public static final String AMOUNT_INVARIANT_VIOLATION = "AMOUNT_INVARIANT_VIOLATION";
    public static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";
    /** 复式记账借贷不平衡：数据质量门禁，拒绝落任何分录（Feature 004 / FR-002）。 */
    public static final String LEDGER_UNBALANCED = "LEDGER_UNBALANCED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
}
