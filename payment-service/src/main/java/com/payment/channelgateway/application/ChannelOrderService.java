package com.payment.channelgateway.application;

import com.payment.channelgateway.domain.ChannelOrder;

/**
 * 渠道单服务（spec 041）：渠道网关域对「渠道单」这一聚合的<b>业务服务</b>端口。
 *
 * <h3>渠道单是什么</h3>
 * <p>渠道单就是<b>渠道层的订单</b>——一次渠道交互（下单 / 退款 / 查询）在平台侧的事实载体，
 * 落 {@code channel_orders} 表，业务单号是 {@code channelNo}（{@code CH}+雪花，spec 037 / FR-001）。
 * 它不是「支付单的一条附属记录」：渠道引用、渠道模态、重试轮次、收敛终态都是渠道侧的事实，
 * 由渠道域自己成形、自己落库。</p>
 *
 * <h3>三层落位（spec 041）</h3>
 * <pre>
 * api/ChannelOrderController      —— 接收请求、转响应（只读查询）
 *      └→ application/ChannelOrderService   —— 业务规则（开单不变量、收敛语义、重复键判据）
 *              └→ domain/ChannelOrderRepository ← infra/persistence/*Mapper  —— 增删改查
 * </pre>
 * payment 侧只经本端口接触渠道单：<b>不 new 渠道单、不收敛渠道单、不写 {@code channel_orders}</b>。
 *
 * <h3>事务（spec 041 / D2：拆分）</h3>
 * <p>渠道单的写<b>不再</b>与 payment 写共享事务——渠道层自己开事务、自己提交。
 * 因此两侧必须各自能独立收敛：payment 落库后渠道单开单失败 ⇒ payment 停在 {@code PENDING}
 * 由主动查询/超时扫描兜底；渠道单终态与 payment 终态出现偏差 ⇒ 由对账收敛。
 * 这是<b>用最终一致换模块自治</b>的取舍，代价是必须补对账缺口（见 spec 041 风险 R2）。</p>
 *
 * <p>实现放在 infra 层（持 {@code ChannelOrderRepository}）——{@code infra → application} 依赖，
 * 不违反 INV-4（{@code application/channel/**} MUST NOT 依赖 {@code infra/channel/**}）。</p>
 */
public interface ChannelOrderService {

    /**
     * 创建一次<b>支付</b>渠道交互（{@code attempt_type=PAYMENT}）。
     *
     * <p><b>Payment 1:1 ChannelOrder（写侧不变量，FIX-3）</b>：实现 MUST 先经
     * {@link #requireNoExistingChannelOrder(String)} 断言该支付单尚无 PAYMENT 尝试行。
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
    ChannelOrder openChannelOrder(String paymentNo, String channelCode,
                                      long amountMinor, String currencyCode);

    /**
     * 断言该支付单<b>尚无</b> PAYMENT 尝试行（Payment 1:1 ChannelOrder，FIX-3）。
     *
     * <p>默认实现挂在端口上，是为了让<b>三个写入口实现共享同一判据</b>
     * （生产 {@code ChannelOrderServiceImpl}、内存测试桩、仓储兼容垫片）——
     * 不变量若在三个实现里各写一遍，迟早漂移成「生产拦住了、测试桩没拦」，
     * 而那种偏差恰恰要等真连渠道才暴露。</p>
     *
     * @throws com.payment.common.core.error.BizException {@code INTERNAL_ERROR}
     *         —— 已有 PAYMENT 尝试行，属数据/编排异常（不是可幂等吸收的重复请求：
     *         幂等重复必须在<b>建单之前</b>由业务唯一键拦下，见 {@code PaymentPersistence.insertPending}）
     */
    default void requireNoExistingChannelOrder(String paymentNo) {
        findChannelOrder(paymentNo).ifPresent(existing -> {
            throw com.payment.common.core.error.BizException.of(
                    com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                    "payment " + paymentNo + " already has a PAYMENT attempt (id=" + existing.getId()
                            + ", status=" + existing.getStatus()
                            + "); Payment 1:1 ChannelOrder violated — idempotent replay must return the"
                            + " stored attempt instead of opening a second one");
        });
    }

    /**
     * 创建一次<b>退款</b>渠道交互（{@code attempt_type=REFUND}）。
     *
     * <p>渠道码 MUST 取自被退支付单的生效支付渠道（INV-6 / FR-005），由调用方解析后传入
     * ——本接口不负责解析，也不回落默认值。</p>
     */
    ChannelOrder openRefundAttempt(String paymentNo, String channelCode,
                                     long amountMinor, String currencyCode);

    /**
     * 记录一次退款渠道交互的<b>最终事实</b>：创建 + 按权威结果收敛 + 落库，一步到位（FIX-4）。
     *
     * <p><b>为什么要有这个方法（而不是让调用方自己 new + converge + save）</b>：
     * {@code channel_orders} 是 Channel Domain 的持久化模型，attempt 的「建行 → 收敛 → 落库」
     * 三段是渠道交互的事实成形过程，属于渠道层。此前 {@code PaymentRefundService}（payment 层）
     * 自己 {@code new ChannelOrder.refundAttempt(…)} + {@code converge} + {@code save}，
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
    ChannelOrder recordRefundAttempt(String paymentNo, String channelCode,
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
    static ChannelOrder requireTrueRefundReplay(
            com.payment.channelgateway.domain.ChannelOrderRepository repository,
            String paymentNo, String channelReference) {
        if (channelReference == null) {
            throw com.payment.common.core.error.BizException.of(
                    com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                    "refund attempt for payment " + paymentNo + " collided on the channel-reference unique key"
                            + " but carries no channelReference; refusing to absorb silently");
        }
        return repository.findByPaymentNo(paymentNo).stream()
                .filter(a -> ChannelOrder.TYPE_REFUND.equals(a.getAttemptType()))
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
     * 把权威渠道结果收敛到渠道单状态机（{@code accept} / {@code succeed} / {@code fail} / {@code markUnknown}）。
     *
     * <p>由渠道层实现，payment 层不直接调用本方法（FR-004：两侧各自推进自己的聚合）。</p>
     *
     * @return 是否发生真正的状态迁移
     */
    boolean converge(ChannelOrder order, ChannelResult result);

    /**
     * 收敛的<b>完整形态</b>（spec 041）：按渠道单 id 载入 → 落重试轮次 → 收敛 → 落库，
     * 全部在渠道侧一个事务内完成。
     *
     * <p><b>为什么 payment 侧不该拆成 require + converge + save 三步</b>：那是渠道单的
     * 「建行 → 收敛 → 落库」事实成形过程，属于渠道域。让 payment 侧代劳，
     * 就等于把渠道单的生命周期编排泄漏到资金动作域（正是改造前的形态）。</p>
     *
     * @param orderId 渠道单自增 id（库内关联键；对外身份仍用 {@code channelNo}）
     * @param retries 本次渠道调用实际发生的重试轮次（ADR-0013 修订：重试不落库，最终一次性写入）
     * @return 已落库的渠道单
     */
    ChannelOrder converge(Long orderId, ChannelResult result, int retries);

    /** 把 attempt 标记为 UNKNOWN（超时/无响应）。 */
    ChannelOrder markUnknown(ChannelOrder attempt, String reason);

    /** 按 id 读取 attempt（收敛路径取回持久态）。 */
    ChannelOrder require(Long attemptId);

    /**
     * 按支付单号取它已记录的 <b>PAYMENT</b> attempt（幂等回放路径用，Feature 028 / FR-003）。
     *
     * <p>返回 {@code Optional.empty()} 表示该支付单没有 PAYMENT 尝试记录——数据异常，
     * 由调用方决定抛出（不静默造一条假记录）。</p>
     */
    java.util.Optional<ChannelOrder> findChannelOrder(String paymentNo);

    /** 落库一次渠道单变更（渠道侧独立事务）。 */
    ChannelOrder save(ChannelOrder order);

    /** 按渠道单业务号查询（只读；{@code channel_no} 唯一，至多一条）。 */
    java.util.Optional<ChannelOrder> findByChannelNo(String channelNo);

    /** 按支付单号列出全部渠道单（含 PAYMENT / REFUND，只读）。 */
    java.util.List<ChannelOrder> findByPaymentNo(String paymentNo);
}
