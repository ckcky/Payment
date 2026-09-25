package com.payment.channelgateway.application;

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
     * <p><b>Payment 1:1 PaymentAttempt（写侧不变量，FIX-3）</b>：实现 MUST 先经
     * {@link #requireNoExistingPaymentAttempt(String)} 断言该支付单尚无 PAYMENT 尝试行。
     * 该基数关系是支付域的核心事实——一条支付单对应一次渠道交互；放过第二条会让
     * 「这笔钱走的哪个渠道 / 哪条渠道引用」变成多值，退款取生效渠道、对账取渠道流水号
     * 都会开始各取各的（INV-6 / FR-005 依赖的正是「唯一那条 SUCCEEDED 的 PAYMENT 行」）。</p>
     *
     * <p><b>为什么不在库里用唯一约束表达</b>：需要的是「{@code (payment_no)} 在
     * {@code attempt_type='PAYMENT'} 子集上唯一」，而 MySQL 不支持部分索引；退而求其次的
     * {@code UNIQUE (payment_no, attempt_type)} 会连<b>合法的多条 REFUND 尝试</b>一起禁掉
     * （部分退款 / 多次退款尝试是正常业务）。故该不变量只能落在写入口这一层。</p>
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
     * 断言该支付单<b>尚无</b> PAYMENT 尝试行（Payment 1:1 PaymentAttempt，FIX-3）。
     *
     * <p>默认实现挂在端口上，是为了让<b>三个写入口实现共享同一判据</b>
     * （生产 {@code ChannelAttemptRecorderImpl}、内存测试桩、仓储兼容垫片）——
     * 不变量若在三个实现里各写一遍，迟早漂移成「生产拦住了、测试桩没拦」，
     * 而那种偏差恰恰要等真连渠道才暴露。</p>
     *
     * @throws com.payment.common.core.error.BizException {@code INTERNAL_ERROR}
     *         —— 已有 PAYMENT 尝试行，属数据/编排异常（不是可幂等吸收的重复请求：
     *         幂等重复必须在<b>建单之前</b>由业务唯一键拦下，见 {@code PaymentPersistence.insertPending}）
     */
    default void requireNoExistingPaymentAttempt(String paymentNo) {
        findPaymentAttempt(paymentNo).ifPresent(existing -> {
            throw com.payment.common.core.error.BizException.of(
                    com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                    "payment " + paymentNo + " already has a PAYMENT attempt (id=" + existing.getId()
                            + ", status=" + existing.getStatus()
                            + "); Payment 1:1 PaymentAttempt violated — idempotent replay must return the"
                            + " stored attempt instead of opening a second one");
        });
    }

    /**
     * 创建一次<b>退款</b>渠道交互（{@code attempt_type=REFUND}）。
     *
     * <p>渠道码 MUST 取自被退支付单的生效支付渠道（INV-6 / FR-005），由调用方解析后传入
     * ——本接口不负责解析，也不回落默认值。</p>
     */
    PaymentAttempt openRefundAttempt(String paymentNo, String channelCode,
                                     long amountMinor, String currencyCode);

    /**
     * 记录一次退款渠道交互的<b>最终事实</b>：创建 + 按权威结果收敛 + 落库，一步到位（FIX-4）。
     *
     * <p><b>为什么要有这个方法（而不是让调用方自己 new + converge + save）</b>：
     * {@code payment_attempts} 是 Channel Domain 的持久化模型，attempt 的「建行 → 收敛 → 落库」
     * 三段是渠道交互的事实成形过程，属于渠道层。此前 {@code PaymentRefundService}（payment 层）
     * 自己 {@code new PaymentAttempt.refundAttempt(…)} + {@code converge} + {@code save}，
     * 并在自己这边 {@code catch (DuplicateKeyException)} 把<b>一切</b>重复键当幂等吸收——
     * 结果是「渠道引用的值本身就写错了」这类缺陷被伪装成「幂等重放」（即 F5 被静默掩盖的成因）。
     * 判「重复键意味着什么」需要知道渠道引用的语义，只有渠道层能答。</p>
     *
     * <p><b>重复键语义（本方法实现 MUST 遵守）</b>：撞 {@code uk_attempts_channel_reference}
     * 时<b>不得</b>无条件吸收。仅当「同一 {@code paymentNo} 下已存在<b>同 {@code channelReference}</b>
     * 的 {@code REFUND} 行」时，才算真幂等重放并按该行吸收（{@link #requireTrueRefundReplay}）；
     * 其余情况 MUST 抛错——那说明引用值本身有问题，静默吸收等于丢掉这笔退款的渠道流水号。</p>
     *
     * @param paymentNo        所属支付单号
     * @param channelCode      被退支付单的生效支付渠道码（INV-6）
     * @param amountMinor      <b>所属支付单</b>金额（spec 018 / D2：REFUND 行记支付单金额，不是退款金额）
     * @param currencyCode     币种
     * @param result           渠道权威结果（决定 REFUND 行的终态与渠道引用）
     * @return 已落库的 REFUND attempt
     */
    PaymentAttempt recordRefundAttempt(String paymentNo, String channelCode,
                                       long amountMinor, String currencyCode, ChannelResult result);

    /**
     * 撞 {@code uk_attempts_channel_reference} 时的判据（FIX-4）：只有「同一支付单已存在
     * <b>同引用</b>的 {@code REFUND} 行」才算真幂等重放并吸收，否则抛错。
     *
     * <p>把它放在端口上（static），是为了让三个写入口实现共享同一判据——不变量若各写一遍，
     * 迟早漂移成「生产拦住了、测试桩没拦」。</p>
     *
     * <p><b>为什么必须区分</b>：唯一约束是<b>单列</b>的（{@code UNIQUE KEY (channel_reference)}），
     * 它不区分行类型。若退款侧把「原支付交易号」当成退款流水号写进去（F5 的形态），
     * 撞的其实是同支付单那条 <b>PAYMENT</b> 行的引用——那不是重放，而是把退款的事实写丢了。
     * 无条件吸收会让这类缺陷长期隐身。</p>
     *
     * @throws com.payment.common.core.error.BizException {@code INTERNAL_ERROR} 引用值有误 / 非本支付单的退款引用
     */
    static PaymentAttempt requireTrueRefundReplay(
            com.payment.payment.domain.PaymentAttemptRepository repository,
            String paymentNo, String channelReference) {
        if (channelReference == null) {
            throw com.payment.common.core.error.BizException.of(
                    com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                    "refund attempt for payment " + paymentNo + " collided on the channel-reference unique key"
                            + " but carries no channelReference; refusing to absorb silently");
        }
        return repository.findByPaymentNo(paymentNo).stream()
                .filter(a -> PaymentAttempt.TYPE_REFUND.equals(a.getAttemptType()))
                .filter(a -> channelReference.equals(a.getChannelReference()))
                .findFirst()
                .orElseThrow(() -> com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                        "refund channelReference conflict on payment " + paymentNo + ": ref=" + channelReference
                                + " collided with uk_attempts_channel_reference, but no REFUND attempt of this"
                                + " payment carries it — the reference value itself is wrong (a non-refund row"
                                + " owns it, e.g. the original trade_no was used as the refund reference)."
                                + " Refusing to absorb silently (see acceptance.md F5)"));
    }

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
