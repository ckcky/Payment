package com.payment.channelgateway.application;

/**
 * 主动查询请求：携带平台侧标识，供渠道按自身引用/交易号定位状态（spec US2 / ADR-0003）。
 *
 * <p>跨系统标识一律业务单号（ADR-0063）：{@code paymentNo}（PM+雪花），禁止数值 paymentId。</p>
 *
 * <h3>spec 030 契约扩展（FR-107）</h3>
 * 新增 {@code channelTransactionId}。<b>⚠️ 注意区分</b>：{@code transactionId} 是
 * <b>平台侧</b>交易号（TX+雪花），<b>不是渠道交易号</b>；渠道定位原交易需要的是
 * {@code channelTransactionId}（即 {@code channel_orders.channel_reference}）。
 * 修复前 {@code ChannelQueryService} 错把平台交易号当渠道交易号传（C-12 / S21），
 * 该调用侧由 Phase 6 / T60 同步修正。
 *
 * <p><b>保留 3 参兼容构造器</b>。</p>
 */
public record QueryStatusRequest(String paymentNo, String transactionId, String idempotencyKey,
                                 String channelTransactionId) {

    /** 兼容构造器（spec 030 前的既有形态）：无渠道交易号。 */
    public QueryStatusRequest(String paymentNo, String transactionId, String idempotencyKey) {
        this(paymentNo, transactionId, idempotencyKey, null);
    }
}
