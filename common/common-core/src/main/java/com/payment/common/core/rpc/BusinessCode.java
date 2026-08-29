package com.payment.common.core.rpc;

/**
 * 业务响应码：通信成功后，下游对这笔业务给出的结论（ADR-0012）。
 *
 * <p>它<b>不参与重试判定</b>——业务拒绝是明确结论，重试没有意义（FR-006）。
 * 重试只看 {@link TransportCode}。</p>
 *
 * <p>通信失败时本码 MUST 为 {@link #UNKNOWN}；只有通信成功但下游未给出明确业务结论时，
 * 才允许出现「通信成功 + 业务 UNKNOWN」，此时调用方不得臆断成败。</p>
 */
public enum BusinessCode {

    /** 业务成功。 */
    SUCCESS,

    /** 下游明确拒绝（通用拒绝）。 */
    DECLINED,

    /** 余额/额度不足。 */
    INSUFFICIENT_FUNDS,

    /** 风控拒绝。 */
    RISK_REJECTED,

    /** 请求本身不合法（参数、签名、状态前置条件不满足）。 */
    INVALID_REQUEST,

    /** 重复请求（下游已处理过，幂等命中）。 */
    DUPLICATE,

    /** 无明确业务结论：通信失败时，或通信成功但下游未给出结论。 */
    UNKNOWN;

    /** 业务结论是否为成功。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** 业务结论是否明确（可用于判定成败）；为 false 时不得臆断成败。 */
    public boolean isConclusive() {
        return this != UNKNOWN;
    }
}
