package com.payment.common.dto.rpc;

/**
 * 记账事件类型（spec 031 §6.1，ADR-0077）：上游只陈述「发生了什么」，
 * 由 Ledger 依此选 Posting Rule 决定「应该怎么记」。契约枚举，禁止字符串字面量散落他域。
 */
public enum AccountingEventType {

    /** 支付成功确认（sourceId = paymentNo）。 */
    PAYMENT_CAPTURE,
    /** 退款成功确认（sourceId = refundNo）。 */
    REFUND,
    /** 渠道手续费独立确认（sourceId = 费单/来源单号）。 */
    CHANNEL_FEE,
    /** 渠道清算到账（sourceId = 清算单号；031 交付契约+规则，产生链路 032+）。 */
    CHANNEL_SETTLEMENT,
    /** 商户结算结转（sourceId = batchNo，净额带符号）。 */
    MERCHANT_SETTLEMENT,
    /** 挂账 / 调账（sourceId = adjustNo；017 五类调账的事件化）。 */
    ADJUSTMENT
}
