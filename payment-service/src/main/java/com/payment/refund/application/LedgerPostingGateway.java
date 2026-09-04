package com.payment.refund.application;

/**
 * 退款记账出站网关（Feature 005 / ADR-0018）：refund-service → ledger-service 的同步 RPC 边界。
 *
 * <p>仅对**已确认**的退款成功发起记账（SUCCEEDED / PARTIALLY_SUCCEEDED，金额 = 已确认退款额），
 * UNKNOWN/PROCESSING/FAILED/REJECTED 不记账（Constitution §V.7）。记账 RPC 失败**不回滚**退款成功
 * 事实（禁 2PC），由实现方记录待记账并交重试/对账兜底。</p>
 */
public interface LedgerPostingGateway {

    /**
     * 退款冲正记账：幂等键 {@code REFUND:<idempotencyKey>}，借贷由账本服务校验平衡。
     *
     * @param idempotencyKey 退款幂等键（账本据此幂等吸收重复）
     * @param refundId       退款 ID（业务来源 ID）
     * @param amountMinor    实际退款金额（最小货币单位，须 > 0）
     * @param currencyCode   币种
     */
    void postRefundCapture(String idempotencyKey, Long refundId, long amountMinor, String currencyCode);
}
