package com.payment.payment.application;

/**
 * 记账出站网关（Feature 004 / ADR-0009，031 事件化改造 / ADR-0077）：
 * payment-service → ledger-service 的同步 RPC 边界。
 *
 * <p>031 起 payment 只发<b>已确认的支付事实</b>（PAYMENT_CAPTURE 事件）：不组分录、
 * 不知道科目与借贷方向（原则 1~3），也不再拼接幂等键——账本按
 * {@code PAYMENT_CAPTURE:{paymentNo}} 派生（原则 10）。</p>
 *
 * <p>仅对**已确认**的支付成功发起记账（UNKNOWN/PROCESSING 不记账，Constitution §V.7）。
 * 记账 RPC 失败**不回滚**支付成功事实（禁 2PC），由实现方记录指标与告警，
 * 交对账 {@code MISSING_POSTING} 兜底。</p>
 */
public interface LedgerPostingGateway {

    /** 支付成功事实记账（幂等由账本派生键吸收重复）。 */
    void postPaymentCapture(PaymentCaptureFacts facts);

    /**
     * 已确认支付事实（Financial Fact）：金额是算好的数字（minor），不是费率（原则 4 的对偶——
     * 费率计算属上游，MVP 两费恒 0，费率建模见后续 Feature）。
     *
     * @param paymentNo        支付业务单号 PM+雪花（账本 sourceId，ADR-0063）
     * @param merchantId       商户号（spec 031 / §13：payments 新列带来的事实）
     * @param channelCode      成功 attempt 的渠道码（渠道应收分户锚）
     * @param grossAmountMinor 用户实付毛额
     * @param merchantFeeMinor 商户费事实（MVP 恒 0）
     * @param channelFeeMinor  渠道费事实（MVP 恒 0）
     * @param currencyCode     币种
     */
    record PaymentCaptureFacts(String paymentNo, String merchantId, String channelCode,
                               long grossAmountMinor, long merchantFeeMinor, long channelFeeMinor,
                               String currencyCode) {
    }
}
