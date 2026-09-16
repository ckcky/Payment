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
    /** 订单已不可支付（取消/超时/关闭）仍收到支付成功回写（Feature 015 / INV-1，HTTP 409）。
     *  payment-service 捕获后触发自动退款（ADR-015），不得吞掉。 */
    public static final String ORDER_NOT_PAYABLE = "ORDER_NOT_PAYABLE";
    /** 对账批次尚有未处理差异却尝试关闭（ADR-0019 关闭门禁）。 */
    public static final String UNRESOLVED_DIFFERENCES = "UNRESOLVED_DIFFERENCES";
    public static final String AMOUNT_INVARIANT_VIOLATION = "AMOUNT_INVARIANT_VIOLATION";
    public static final String UNKNOWN_STATUS = "UNKNOWN_STATUS";
    /** 复式记账借贷不平衡：数据质量门禁，拒绝落任何分录（Feature 004 / FR-002）。 */
    public static final String LEDGER_UNBALANCED = "LEDGER_UNBALANCED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** 无可用渠道：全部渠道被禁用/不可用，且调用方未指定渠道（Feature 028 / FR-016，HTTP 409）。
     *  此时<b>不产生任何支付单落库</b>——不允许部分写入。 */
    public static final String NO_AVAILABLE_CHANNEL = "NO_AVAILABLE_CHANNEL";
    /** 显式指定的渠道当前不可用（availability=DOWN）：明确拒绝而非静默改选（Feature 028 / FR-034，HTTP 409）。
     *  不篡改调用方意图——偷偷改选等于替用户做了资金路径决策。 */
    public static final String CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE";
    /** 用户支付限额超限：日 / 月 / 年任一周期本笔金额放不下（spec 027 / FR-019，HTTP 409）。
     *  语义是「支付单<b>未创建</b>」而非「创建了再拒」——建单事务整体回滚，`payments` 表无新增行（INV-3）。
     *  错误消息 MUST 说明<b>哪个周期</b>超限与当前额度（对齐 ADR-0049「给出合法取值清单」）。 */
    public static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
}
