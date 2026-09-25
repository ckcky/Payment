package com.payment.channelgateway.application;

import com.payment.common.core.rpc.BusinessCode;
import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.domain.PaymentAttemptErrorType;

import com.payment.common.dto.channel.PayCredential;
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
                            TransportCode transportCode, BusinessCode businessCode,
                            PayCredential credential) {

    public enum Status {
        SUCCESS,
        FAILURE,
        UNKNOWN
    }

    /**
     * 兼容构造（spec 030 前的既有 5 参形态）：无凭证。
     *
     * <p>既有 21 个引用 {@code ChannelResult} 的测试文件与全部既有工厂零改动（SC-A-02）。</p>
     */
    public ChannelResult(Status status, String channelReference, String reason,
                         TransportCode transportCode, BusinessCode businessCode) {
        this(status, channelReference, reason, transportCode, businessCode, null);
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

    /**
     * 渠道受理（spec 019 / D7）：退款请求已被渠道接受、结果待异步回调推送。
     * 语义 = 通信成功 + 业务无结论（UNKNOWN，带渠道受理流水号），调用方据此保持待收敛态。
     */
    public static ChannelResult accepted(String channelReference, String reason) {
        return accepted(channelReference, reason, null);
    }

    /**
     * 渠道受理（spec 030 / FR-112）：<b>携带付款凭证</b>。
     *
     * <p>用于「渠道已受理、买家尚未付款」的支付场景——例如支付宝 {@code alipay.trade.page.pay}
     * 返回跳转签名 URL。此时 payment <b>MUST 停在 {@code PROCESSING}</b>，
     * <b>MUST NOT</b> 走成功收敛路径（INV-6）：钱还没到，记账与通知都不得发生。</p>
     *
     * @param channelReference 渠道受理流水号（可能为 {@code null}：下单阶段渠道常还没给交易号）
     * @param reason           受理说明
     * @param credential       付款凭证（可空；非空即代表「待买家付款」）
     */
    public static ChannelResult accepted(String channelReference, String reason,
                                         PayCredential credential) {
        ChannelResult base = of(TransportCode.SUCCESS, BusinessCode.UNKNOWN, channelReference, reason);
        return new ChannelResult(base.status, base.channelReference, base.reason,
                base.transportCode, base.businessCode, credential);
    }

    /** 是否携带付款凭证（非空即「渠道已受理、买家未付款」，INV-6）。 */
    public boolean hasCredential() {
        return credential != null;
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

    /**
     * 返回携带新 reason 的副本（重试耗尽时用它标注 {@code RETRY_EXHAUSTED}）。
     *
     * <p>spec 030 / FR-112：<b>MUST 保留 {@code credential}</b>——重试是同一笔渠道交互的延续，
     * 凭证不应在改 reason 时被抹掉（抹掉会让「买家去哪儿付款」这个信息丢失，
     * 而支付单还停在 PROCESSING 等着买家付款）。</p>
     */
    public ChannelResult withReason(String newReason) {
        return new ChannelResult(status, channelReference, newReason, transportCode, businessCode,
                credential);
    }
}
