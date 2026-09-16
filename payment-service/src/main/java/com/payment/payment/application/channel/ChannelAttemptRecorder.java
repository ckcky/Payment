package com.payment.payment.application.channel;

import com.payment.payment.domain.PaymentAttempt;

/**
 * 渠道层写入口（Feature 028 / FR-002，ADR-0072 / INV-5）：
 * {@code payment_attempts} 表的<b>唯一</b>创建与收敛入口。
 *
 * <p><b>为什么需要这个端口</b>：Feature 028 前，{@code PaymentPersistence}（payment 层）在同一事务内
 * 直接读写 {@code PaymentAttemptRepository}，两层的表写入口混在一起（spec §1.2 S1）。本端口把
 * attempt 的写职责收归渠道层——payment 层只写 {@code payments}，渠道层只写 {@code payment_attempts}。</p>
 *
 * <p><b>边界澄清（INV-5 尾注）</b>：这是<b>职责分层，不是拆事务</b>。两层仍共享同一个本地事务
 * ——{@code payments} 与 {@code payment_attempts} 的状态必须同时迁移，否则出现
 * {@code payment=SUCCEEDED / attempt=PENDING} 之类的永久不一致。</p>
 *
 * <p>实现放在 infra 层（持 {@code PaymentAttemptRepository}）——{@code infra → infra} 依赖，
 * 不违反 INV-4（{@code application/channel/**} MUST NOT 依赖 {@code infra/channel/**}）。</p>
 */
public interface ChannelAttemptRecorder {

    /**
     * 创建一次<b>支付</b>渠道交互（{@code attempt_type=PAYMENT}）。
     *
     * @param paymentNo    所属支付单号
     * @param channelCode  <b>路由后最终</b>渠道码（FR-028：幂等键与 attempt 行共用同一解析值）
     * @param amountMinor  本次渠道交互金额（最小货币单位）
     * @param currencyCode 币种
     * @return 已落库（含 id）的 attempt
     */
    PaymentAttempt openPaymentAttempt(String paymentNo, String channelCode,
                                      long amountMinor, String currencyCode);

    /**
     * 创建一次<b>退款</b>渠道交互（{@code attempt_type=REFUND}）。
     *
     * <p>渠道码 MUST 取自被退支付单的生效支付渠道（INV-6 / FR-005），由调用方解析后传入
     * ——本接口不负责解析，也不回落默认值。</p>
     */
    PaymentAttempt openRefundAttempt(String paymentNo, String channelCode,
                                     long amountMinor, String currencyCode);

    /**
     * 把权威渠道结果收敛到 attempt 状态机（{@code accept} / {@code succeed} / {@code fail} / {@code markUnknown}）。
     *
     * <p>由渠道层实现，payment 层不直接调用本方法（FR-004：两侧各自推进自己的聚合）。</p>
     *
     * @return 是否发生真正的状态迁移
     */
    boolean converge(PaymentAttempt attempt, ChannelResult result);

    /** 把 attempt 标记为 UNKNOWN（超时/无响应）。 */
    PaymentAttempt markUnknown(PaymentAttempt attempt, String reason);

    /** 按 id 读取 attempt（收敛路径取回持久态）。 */
    PaymentAttempt require(Long attemptId);

    /**
     * 按支付单号取它已记录的 <b>PAYMENT</b> attempt（幂等回放路径用，Feature 028 / FR-003）。
     *
     * <p>返回 {@code Optional.empty()} 表示该支付单没有 PAYMENT 尝试记录——数据异常，
     * 由调用方决定抛出（不静默造一条假记录）。</p>
     */
    java.util.Optional<PaymentAttempt> findPaymentAttempt(String paymentNo);

    /** 落库一次 attempt 变更（同一本地事务内由调用方编排）。 */
    PaymentAttempt save(PaymentAttempt attempt);
}
