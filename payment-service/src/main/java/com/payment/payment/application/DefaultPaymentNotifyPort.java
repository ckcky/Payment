package com.payment.payment.application;

import com.payment.channelgateway.application.ChannelResult;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.channel.ChannelPayNotified;
import com.payment.common.dto.channel.ChannelRefundNotified;
import com.payment.payment.application.refund.RefundRpcCallbackService;
import com.payment.payment.domain.Payment;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderRepository;
import com.payment.payment.domain.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link PaymentNotifyPort} 的默认实现（spec 037 / T5 / FR-010 / FR-011 / FR-012）。
 *
 * <h3>它是从哪来的</h3>
 * <p>支付回调的<b>业务校验</b>原先直接写在 {@code ChannelPluginCallbackController.validate()} 里
 * （渠道域的回调入口）：查 payment、验渠道引用归属、验金额币种。本类把这部分逻辑
 * <b>逐字搬进 Payment 域</b>（FR-011），并保持三件套（不推进 + 计指标 + 写审计）与
 * 异常分档（{@code processing_error} / {@code unexpected_error}）<b>完全同口径</b>——
 * 这是 NFR-2 意义上的机械搬迁，不是重新设计。</p>
 *
 * <h3>与支付宝专属端点合并时的<b>唯一</b>口径分歧（T6）</h3>
 * <p>T6 把支付宝 notify 路径也收进本端口后，两条拒绝路径的<b>审计币种</b>不一致：
 * 专属端点写死 {@code "CNY"}，本端口写 {@code null}。合并后取「被拒支付单自己的币种」——
 * 对 CNY 单与专属端点的既有记录逐字相同（既有断言零变化），且任何币种下记录的都是事实。
 * 详见 {@link #reject}。</p>
 *
 * <h3>退款侧</h3>
 * <p>{@link #onChannelRefundResult(ChannelRefundNotified)} 取代了 {@code RefundResultListener}
 * 的 {@code MockRefundResultBridge} 实现（FR-012）：接口定义权归 Payment，渠道网关只依赖接口。
 * 收敛仍然复用 {@link RefundRpcCallbackService#handleChannelCallback}，与真实渠道的 HTTP 回调
 * 端点走同一路径。</p>
 *
 * <h3>为什么不在这里读染色上下文</h3>
 * <p>模态判定内聚在渠道网关域（FR-013）：回调路径的 {@code DyeContext.runWith(SANDBOX, …)}
 * 由网关域的 {@code ChannelCallbackHandler} 包裹本端口调用。Payment 侧因此不出现
 * {@code DyeContext}——它只处理「渠道说这笔钱怎么样了」这个业务问题。</p>
 */
@Service
public class DefaultPaymentNotifyPort implements PaymentNotifyPort {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentNotifyPort.class);

    private static final String MODULE = "payment";

    private final PaymentCallbackService payCallbackService;
    private final RefundRpcCallbackService refundCallbackService;
    private final PaymentRepository paymentRepository;
    private final ChannelOrderRepository attemptRepository;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public DefaultPaymentNotifyPort(PaymentCallbackService payCallbackService,
                                    RefundRpcCallbackService refundCallbackService,
                                    PaymentRepository paymentRepository,
                                    ChannelOrderRepository attemptRepository,
                                    BusinessMetrics metrics,
                                    StructuredAuditLogger auditLogger) {
        this.payCallbackService = payCallbackService;
        this.refundCallbackService = refundCallbackService;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    @Override
    public PayNotifyOutcome onChannelPayResult(ChannelPayNotified notified) {
        String paymentNo = notified.paymentNo();
        String channelCode = notified.channelCode();
        try {
            Payment payment = paymentRepository.findByPaymentNo(paymentNo).orElse(null);
            if (payment == null) {
                // 找不到支付单：可能是往期数据或串号。交由下面的 catch 记日志 + 非 success 响应
                throw BizException.of(ErrorCodes.NOT_FOUND,
                        "payment not found for callback: " + paymentNo);
            }
            String rejection = validate(notified, payment);
            if (rejection != null) {
                reject(payment, rejection);
                return PayNotifyOutcome.rejected(rejection);
            }
            // 收敛复用既有链路（终态吸收 + 乱序保护 + 幂等，全部由它保证）
            payCallbackService.handleCallback(paymentNo, toResult(notified));
            return PayNotifyOutcome.accepted();
        } catch (BizException ex) {
            log.warn("channel callback processing failed channel={} paymentNo={} reason={}",
                    channelCode, paymentNo, ex.getMessage());
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "processing_error");
            return PayNotifyOutcome.error(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("channel callback unexpected error channel={} paymentNo={}", channelCode, paymentNo, ex);
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "unexpected_error");
            return PayNotifyOutcome.error(ex.getClass().getSimpleName());
        }
    }

    @Override
    public void onChannelRefundResult(ChannelRefundNotified notified) {
        refundCallbackService.handleChannelCallback(notified.refundNo(), toResult(notified));
    }

    /**
     * 业务侧校验（②渠道引用 + ③金额/币种）。返回 {@code null} 表示全部通过。
     *
     * <p>口径与迁移前的 {@code ChannelPluginCallbackController#validate} 逐字一致；
     * 渠道私有的身份校验（如支付宝 {@code app_id}）不在这里——那属于「①签名/身份」段，
     * 由渠道插件负责。</p>
     *
     * @param payment 已加载的支付单（由调用方加载一次并传入：拒绝审计也要用到它的币种，
     *                在校验里再查一次库只是为了多一次未必命中的读）
     */
    private String validate(ChannelPayNotified notified, Payment payment) {
        // ---- ② Channel Reference Validation ----
        String channelReference = notified.channelTransactionId();
        if (channelReference != null && !channelReference.isBlank()) {
            String refRejection = validateChannelReference(payment.getPaymentNo(), channelReference);
            if (refRejection != null) {
                return refRejection;
            }
        }

        // ---- ③ Amount / Currency Validation ----
        if (notified.hasAmount()) {
            if (notified.amountMinor() != payment.getAmountMinor()) {
                return "amount mismatch: notified=" + notified.amountMinor()
                        + " expected=" + payment.getAmountMinor();
            }
            if (!notified.currencyCode().equalsIgnoreCase(payment.getCurrencyCode())) {
                return "currency mismatch: notified=" + notified.currencyCode()
                        + " expected=" + payment.getCurrencyCode();
            }
        }
        return null;
    }

    /**
     * 渠道引用校验：本单已记录的引用与通知不一致 ⇒ 拒绝（串号检查）。
     *
     * <p>例外：受理阶段渠道可能还没给流水号（{@code null}），首次通知正是回填时机，
     * 不算不一致。</p>
     */
    private String validateChannelReference(String paymentNo, String channelReference) {
        for (ChannelOrder candidate : attemptRepository.findByPaymentNo(paymentNo)) {
            if (ChannelOrder.TYPE_PAYMENT.equals(candidate.getAttemptType())
                    && candidate.getChannelReference() != null
                    && !candidate.getChannelReference().equals(channelReference)) {
                return "channel reference mismatch: recorded=" + candidate.getChannelReference()
                        + " notified=" + channelReference;
            }
        }
        return null;
    }

    /**
     * 校验失败的三件套（FR-213）：不推进状态 + 计指标 + 写审计，缺一不可。
     *
     * <p><b>审计的币种取「被拒的那笔单的币种」</b>——这是 T6 把两条拒绝路径合并成一条时
     * 的<b>一致性选择</b>，不是随手填的值。合并前的两种记录方式都不对：</p>
     * <ul>
     *   <li>支付宝专属端点写死 {@code "CNY"}——在「币种不符」这个场景下，
     *       记录恰好是最误导的一种（平台单是 USD，审计却说 CNY）；</li>
     *   <li>通用端点写 {@code null}——资金面分歧的审计里缺了币种，跨币种对账时无法自证。</li>
     * </ul>
     * <p>取支付单自己的币种两者兼得：对 CNY 单与支付宝路径的既有记录逐字相同，
     * 且任何币种下记录的都是事实。</p>
     */
    private void reject(Payment payment, String reason) {
        String paymentNo = payment.getPaymentNo();
        metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", classify(reason));
        auditLogger.audit("payment.notify_rejected", paymentNo, null, payment.getCurrencyCode(),
                "NOTIFY_RECEIVED", "REJECTED", "payment", paymentNo);
        log.warn("channel callback rejected paymentNo={} reason={}", paymentNo, reason);
    }

    private static String classify(String reason) {
        if (reason.startsWith("amount")) {
            return "amount";
        }
        if (reason.startsWith("currency")) {
            return "currency";
        }
        if (reason.startsWith("channel reference")) {
            return "channel_reference";
        }
        return "other";
    }

    /** 跨域事件 → 网关域结果：三档结论 + 渠道流水号 + 原因，逐字还原（含 UNKNOWN 的流水号）。 */
    private static ChannelResult toResult(ChannelPayNotified notified) {
        return ChannelResult.fromNotified(notified.status(),
                notified.channelTransactionId(), notified.reason());
    }

    private static ChannelResult toResult(ChannelRefundNotified notified) {
        return ChannelResult.fromNotified(notified.status(),
                notified.channelTransactionId(), notified.reason());
    }
}
