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
 *
 * <p>spec 034 §6.3 / H-034-3（C-23）：{@code late} 标记「订单取消后渠道迟到成功」——
 * Payment 已 CLOSED（终态吸收，不复活），本通知是「渠道已收款」追回信号，order 既有
 * ORDER_NOT_PAYABLE surplus 分支据此自动原路退回。非破坏性加字段：旧版消费端按
 * Jackson 默认忽略未知字段（灰度顺序无约束）；7 参兼容构造保留（late=false），
 * 既有调用方零改动。</p>
 */
public record PaymentSucceededRequest(String paymentNo, String orderNo, String transactionNo,
                                      String userId, long amountMinor, String currencyCode,
                                      List<ItemLine> items, boolean late) {

    /** 订单明细行（下单时刻快照，spec 018 FR-005）。 */
    public record ItemLine(String orderItemNo, String skuCode, String name,
                           int quantity, long priceMinor, String currencyCode) {
    }

    /** 兼容构造（spec 034 前形态）：late=false，既有调用方零改动（SC-012）。 */
    public PaymentSucceededRequest(String paymentNo, String orderNo, String transactionNo,
                                   String userId, long amountMinor, String currencyCode,
                                   List<ItemLine> items) {
        this(paymentNo, orderNo, transactionNo, userId, amountMinor, currencyCode, items, false);
    }

    /** payment 侧便捷构造（无明细，明细由 order 层富化）。 */
    public static PaymentSucceededRequest withoutItems(String paymentNo, String orderNo,
                                                       String transactionNo, String userId,
                                                       long amountMinor, String currencyCode) {
        return new PaymentSucceededRequest(paymentNo, orderNo, transactionNo, userId,
                amountMinor, currencyCode, null);
    }

    /**
     * 标记迟到成功（spec 034 / T18）：同载荷、late=true。原请求重发不改写其余字段
     * （台账重放 = 原载荷原文，不在此重算）。
     */
    public PaymentSucceededRequest withLate(boolean late) {
        return new PaymentSucceededRequest(paymentNo, orderNo, transactionNo, userId,
                amountMinor, currencyCode, items, late);
    }
}
