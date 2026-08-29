package com.payment.common.core.rpc;

/**
 * 通信响应码：一次出站调用「通信层面」是否成功完成（ADR-0012）。
 *
 * <p>它是重试判定的唯一依据：<b>非 {@link #SUCCESS} 一律视为通信失败并可重试</b>（含 {@link #TIMEOUT}）。
 * 与之相对，{@link BusinessCode} 只在通信成功后才有效，描述下游的业务结论。</p>
 *
 * <p>通信失败时业务结论不存在，此时 {@link BusinessCode} MUST 记为 {@link BusinessCode#UNKNOWN}，
 * 不得伪造（Constitution §V.7：不把「未确认」当成功或失败）。</p>
 */
public enum TransportCode {

    /** 通信成功完成，拿到了下游的明确响应。 */
    SUCCESS,

    /** 超出超时预算仍未拿到完整响应（RPC 1s / 外部 HTTP 1.5s，全服务统一）。 */
    TIMEOUT,

    /** 建连失败：连接被拒、DNS 失败、连接超时。 */
    CONNECTION_ERROR,

    /** 连接建立后读写中断：连接重置、对端关闭、读超时之外的 I/O 异常。 */
    IO_ERROR,

    /** 下游返回 5xx：下游自身故障，可能自愈。 */
    SERVER_ERROR,

    /** 协议层错误：4xx 或报文不可解析/反序列化失败。 */
    PROTOCOL_ERROR,

    /** 无法归类：未拿到任何可判定的通信结果。 */
    UNKNOWN;

    /** 通信是否成功完成；只有通信成功后 {@link BusinessCode} 才有意义。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** 是否可重试：通信响应码非 SUCCESS 即重试（ADR-0012 负责人裁决）。 */
    public boolean isRetryable() {
        return this != SUCCESS;
    }
}
