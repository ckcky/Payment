package com.payment.payment.application;

/**
 * 记账出站网关（Feature 004 / ADR-0009）：payment-service → ledger-service 的同步 RPC 边界。
 *
 * <p>仅对**已确认**的支付成功发起记账（UNKNOWN/PROCESSING 不记账，Constitution §V.7）。
 * 记账 RPC 失败**不回滚**支付成功事实（禁 2PC），由实现方记录待记账并交重试/对账兜底。</p>
 */
public interface LedgerPostingGateway {

    /**
     * 支付成功记账：幂等键 {@code PAYMENT:<idempotencyKey>}，借贷由账本服务校验平衡。
     *
     * @param idempotencyKey 支付幂等键（账本据此幂等吸收重复）
     * @param paymentId      支付 ID（业务来源 ID）
     * @param amountMinor    支付金额（最小货币单位）
     * @param feeMinor       平台手续费（最小货币单位，可为 0）
     * @param currencyCode   币种
     */
    void postPaymentCapture(String idempotencyKey, Long paymentId, long amountMinor,
                            long feeMinor, String currencyCode);
}
