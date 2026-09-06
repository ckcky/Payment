package com.payment.common.dto.rpc;

import java.util.List;

/**
 * 支付成功的跨服务 RPC 请求（payment-service → order-service → fulfillment-service）。
 *
 * <p>只携带下游所需的原始事实，不暴露 payment 模块内部实体。Feature 016（ADR-0054）：
 * payment 不再直调 fulfillment，本请求仅通知 order（业务编排者），由 order 层驱动履约；
 * {@code transactionNo} 为交易业务单号（FR-006，payment 侧已知其所属 transaction），
 * order transaction 层据此判定正常到账 / 重复超额（surplus）。</p>
 *
 * <p>Feature 018（ADR-0066）：{@code items} 为订单明细快照（order_item 粒度履约的载体）。
 * payment 侧构造时传 {@code null}（payment 不持有明细）；order 层在
 * {@code onPaymentSucceeded} 中以自身 order_items（含 orderItemNo）富化后转发
 * fulfillment，fulfillment 逐明细建履约。请求方传 {@code null} / 空列表均视为
 * 「由 order 层负责富化」，fulfillment 不依赖 payment 填充。</p>
 */
public record PaymentSucceededRequest(String paymentNo, String orderNo, String transactionNo,
                                      String userId, long amountMinor, String currencyCode,
                                      List<ItemLine> items) {

    /** 订单明细行（下单时刻快照，spec 018 FR-005）。 */
    public record ItemLine(String orderItemNo, String skuCode, String name,
                           int quantity, long priceMinor, String currencyCode) {
    }

    /** payment 侧便捷构造（无明细，明细由 order 层富化）。 */
    public static PaymentSucceededRequest withoutItems(String paymentNo, String orderNo,
                                                       String transactionNo, String userId,
                                                       long amountMinor, String currencyCode) {
        return new PaymentSucceededRequest(paymentNo, orderNo, transactionNo, userId,
                amountMinor, currencyCode, null);
    }
}
