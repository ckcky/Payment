package com.payment.payment.application.channel;

import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.domain.PaymentAttemptErrorType;

/**
 * 渠道交互结果：成功 / 失败 / 未知（超时、断连或不完整响应）。
 *
 * <p>只有渠道明确成功/失败才进入对应终态；{@link Status#UNKNOWN} 绝不臆断成败（Constitution §V.7）。</p>
 *
 * <h3>双响应码是错误分类的唯一来源（ADR-0012）</h3>
 * <ul>
 *   <li>{@code transportCode}（通信响应码）：通信层面是否成功完成。<b>非 SUCCESS 一律可重试</b>，
 *       包括 {@link TransportCode#TIMEOUT}（超时算通信失败）。</li>
 *   <li>{@code businessCode}（业务响应码）：通信成功后下游的业务结论。业务拒绝是明确结论，
 *       <b>不重试</b>（FR-006）；无结论时为 {@link BusinessCode#UNKNOWN}。</li>
 * </ul>
 *
 * <p>{@link #status()} 由双码推导，不靠调用方自报；{@link #errorType()} 同样是派生值。</p>
 */
public record ChannelResult(Status status, String channelReference, String reason,
                            TransportCode transportCode, BusinessCode businessCode) {

    public enum Status {
        SUCCESS,
        FAILURE,
        UNKNOWN
    }

    /**
     * 通信成功 + 业务成功 → 成功。
     *
     * <p><b>ADR-0016 已否决（部分退款不做）</b>：曾短暂存在携带渠道实际退款金额的
     * {@code success(channelReference, refundedMinor)} 重载，现已移除。退款恒按全退处理。</p>
     */
    public static ChannelResult success(String channelReference) {
        return new ChannelResult(Status.SUCCESS, channelReference, null,
                TransportCode.SUCCESS, BusinessCode.SUCCESS);
    }

    /** 通信成功 + 业务明确拒绝 → 业务失败，不重试（FR-006）。 */
    public static ChannelResult businessFailure(String channelReference, String reason, BusinessCode code) {
        if (code == BusinessCode.SUCCESS) {
            throw new IllegalArgumentException("business failure must not use BusinessCode.SUCCESS");
        }
        return of(TransportCode.SUCCESS, code, channelReference, reason);
    }

    /** 通信成功 + 业务拒绝（未细分原因，默认 {@link BusinessCode#DECLINED}）。 */
    public static ChannelResult businessFailure(String channelReference, String reason) {
        return businessFailure(channelReference, reason, BusinessCode.DECLINED);
    }

    /** 通信成功但业务无明确结论 → 不重试，进 UNKNOWN 由主动查询收敛。 */
    public static ChannelResult businessUnknown(String reason) {
        return of(TransportCode.SUCCESS, BusinessCode.UNKNOWN, null, reason);
    }

    /** 通信失败（含超时）→ 可重试（ADR-0012）；重试耗尽后保持本结果的 UNKNOWN 语义。 */
    public static ChannelResult transportFailure(TransportCode transportCode, String reason) {
        if (transportCode == TransportCode.SUCCESS) {
            throw new IllegalArgumentException("transport failure must not use TransportCode.SUCCESS");
        }
        return of(transportCode, BusinessCode.UNKNOWN, null, reason);
    }

    /** 超时快捷方式：等价于 {@code transportFailure(TransportCode.TIMEOUT, reason)}。 */
    public static ChannelResult timeout(String reason) {
        return transportFailure(TransportCode.TIMEOUT, reason);
    }

    private static ChannelResult of(TransportCode transport, BusinessCode business,
                                    String channelReference, String reason) {
        return new ChannelResult(deriveStatus(transport, business), channelReference, reason, transport, business);
    }

    private static Status deriveStatus(TransportCode transport, BusinessCode business) {
        if (!transport.isSuccess()) {
            return Status.UNKNOWN; // 通信失败：既不能算成功也不能算失败
        }
        if (business.isSuccess()) {
            return Status.SUCCESS;
        }
        return business.isConclusive() ? Status.FAILURE : Status.UNKNOWN;
    }

    /**
     * 错误分类（由双响应码派生，供落库观测）：
     * 通信失败 → {@link PaymentAttemptErrorType#TRANSIENT}（可重试，重试耗尽后仍记此值）；
     * 业务明确拒绝 → {@link PaymentAttemptErrorType#HARD}；
     * 业务无结论 → {@link PaymentAttemptErrorType#UNKNOWN}；成功时为 {@code null}。
     */
    public PaymentAttemptErrorType errorType() {
        if (!transportCode.isSuccess()) {
            return PaymentAttemptErrorType.TRANSIENT;
        }
        if (businessCode.isSuccess()) {
            return null;
        }
        return businessCode.isConclusive() ? PaymentAttemptErrorType.HARD : PaymentAttemptErrorType.UNKNOWN;
    }

    /** 是否可重试：<b>只看通信响应码</b>，非 SUCCESS 即重试（ADR-0012）。 */
    public boolean retryable() {
        return transportCode.isRetryable();
    }

    /** 返回携带新 reason 的副本（重试耗尽时用它标注 {@code RETRY_EXHAUSTED}）。 */
    public ChannelResult withReason(String newReason) {
        return new ChannelResult(status, channelReference, newReason, transportCode, businessCode);
    }
}
