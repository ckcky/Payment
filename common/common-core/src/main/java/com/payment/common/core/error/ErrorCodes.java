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
    /** 事件缺必填槽位（spec 031 §6.2）：fail fast，不猜默认值。 */
    public static final String EVENT_FIELD_MISSING = "EVENT_FIELD_MISSING";
    /** 未知记账事件类型（spec 031 §6.1）。 */
    public static final String EVENT_TYPE_UNSUPPORTED = "EVENT_TYPE_UNSUPPORTED";
    /** LEGACY 科目禁止被新事件引用（spec 031 §5.1，ADR-0078）。 */
    public static final String LEDGER_ACCOUNT_LEGACY = "LEDGER_ACCOUNT_LEGACY";
    /** 渠道码在账本无对应清算科目实例：fail fast，不静默走默认（spec 031 §5.4）。 */
    public static final String LEDGER_CHANNEL_UNKNOWN = "LEDGER_CHANNEL_UNKNOWN";
    /** 期间已关账：拒收任何新事件（含 ADJUSTMENT，spec 031 §11，G2/ADR-0079）。 */
    public static final String PERIOD_CLOSED = "PERIOD_CLOSED";
    /** 余额投影与分录事实不一致（spec 031 §10 第三层防护）：告警并可 rebuild 修复。 */
    public static final String BALANCE_PROJECTION_DRIFT = "BALANCE_PROJECTION_DRIFT";

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
    /** 渠道账单不可用：该周期无可用（NORMALIZED）账单导入，对账显式失败（spec 032 §11 #1，HTTP 400）。
     *  替代旧 sample.csv 静默回退——「拿别的周期的账单当真账单对」即假对账。 */
    public static final String STATEMENT_UNAVAILABLE = "STATEMENT_UNAVAILABLE";
}
