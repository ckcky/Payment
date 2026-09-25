package com.payment.payment.application.refund;

/**
 * 退款记账出站网关（spec 031 §9 / ADR-0077，替换 Feature 005 / ADR-0018 的直传分录契约）：
 * refund 域 → ledger-service 的同步 RPC 边界。
 *
 * <p>仅对**已确认**的退款成功发起记账（SUCCEEDED，金额 = 已确认退款额），
 * UNKNOWN/PROCESSING/FAILED/REJECTED 不记账（Constitution §V.7）。记账 RPC 失败**不回滚**退款成功
 * 事实（禁 2PC），由实现方记录指标与告警，交对账兜底（语义不变，变的是「传什么」）。</p>
 *
 * <p>幂等键不在契约里：由账本按 {@code REFUND:{refundNo}} 派生（原则 10），重复永不产生重复分录。</p>
 */
public interface LedgerPostingGateway {

    /** 退款成功事实记账（冲正分录由账本按 REFUND 规则展开，上游不知道科目/借贷）。 */
    void postRefundCapture(RefundCaptureFacts facts);

    /**
     * 已确认退款事实（Financial Fact）。
     *
     * @param refundNo     退款业务单号 PMRF（账本 sourceId，ADR-0063）
     * @param merchantId   所属支付的商户号（反查 payment 事实，spec 031 §9）
     * @param channelCode  生效支付渠道码（INV-6：与被退支付同源）
     * @param amountMinor  实际退款金额（最小货币单位，须 > 0）
     * @param currencyCode 币种
     */
    record RefundCaptureFacts(String refundNo, String merchantId, String channelCode,
                              long amountMinor, String currencyCode) {
    }
}
