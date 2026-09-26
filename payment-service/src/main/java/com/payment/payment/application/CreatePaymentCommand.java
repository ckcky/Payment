package com.payment.payment.application;

import com.payment.common.dto.rpc.CreatePaymentRequest;

/**
 * 创建支付意图命令（应用层输入，独立于 API DTO）。
 */
public record CreatePaymentCommand(String transactionId, String orderNo, String userId,
                                   long amountMinor, String currencyCode, String idempotencyKey,
                                   String channelCode,
                                   /** spec 031 / §13：订单携带的商户号（PAYMENT_CAPTURE 事实锚，可为 null=历史口径）。 */
                                   String merchantId) {

    /**
     * 请求 → 命令（spec 041 / FR-020）：<b>唯一的转换入口</b>。
     *
     * <p>改造前这段逐字段拼装写在 {@code PaymentController#createPayment} 里，
     * 等于让 Controller 承担「请求如何映射成应用层输入」的知识。收进命令自身后，
     * Controller 只写 {@code applicationService.pay(request)} 一行。</p>
     */
    public static CreatePaymentCommand from(CreatePaymentRequest request) {
        return new CreatePaymentCommand(request.transactionId(), request.orderNo(),
                request.userId(), request.amountMinor(), request.currencyCode(),
                request.idempotencyKey(), request.channelCode(), request.merchantId());
    }
}
