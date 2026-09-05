package com.payment.common.dto.rpc;

/**
 * 支付成功的跨服务 RPC 请求（payment-service → order-service）。
 *
 * <p>只携带下游所需的原始事实，不暴露 payment 模块内部实体。Feature 016（ADR-0054）：
 * payment 不再直调 fulfillment，本请求仅通知 order（业务编排者），由 order 层驱动履约；
 * {@code transactionNo} 为交易业务单号（FR-006，payment 侧已知其所属 transaction），
 * order transaction 层据此判定正常到账 / 重复超额（surplus）。</p>
 */
public record PaymentSucceededRequest(String paymentNo, String orderNo, String transactionNo,
                                      String userId, long amountMinor, String currencyCode) {
}
