package com.payment.payment.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.PaymentAmountQueryRequest;
import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.common.dto.rpc.RefundAttemptRequest;
import com.payment.common.dto.rpc.RefundAttemptResponse;
import com.payment.payment.application.channel.ChannelAttemptRecorder;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.application.channel.SingleChannelRegistry;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 退款尝试编排（T053）：为 refund-service 提供支付金额查询与渠道退款尝试。
 *
 * <p>只执行「查询事实」与「渠道退款尝试」并回传结果，不决定退款整体状态（退款决策归属 refund-service）。
 * 渠道 UNKNOWN 原样回传，绝不臆断成败。</p>
 *
 * <p><b>反向路径按记录解析（Feature 028 / FR-024 / INV-6）</b>：退款渠道 MUST 取自被退支付单的
 * <b>生效支付渠道</b>（该 {@code payment_no} 下 {@code attempt_type=PAYMENT} 且 {@code SUCCEEDED}
 * 那一行的 {@code channel_code}），经 {@link ChannelRegistry} 解析出渠道实现——
 * <b>绝不调用 Router</b>。资金安全红线：退款换渠道 = 钱退错地方。</p>
 */
@Service
public class PaymentRefundService {

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ChannelRegistry channelRegistry;
    private final ChannelAttemptRecorder attemptRecorder;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    /** 生产主构造：Spring 必须确定地选它（另有测试用兼容构造，故显式标注）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentRefundService(PaymentRepository paymentRepository,
                                PaymentAttemptRepository attemptRepository,
                                ChannelRegistry channelRegistry,
                                ChannelAttemptRecorder attemptRecorder,
                                BusinessMetrics metrics,
                                StructuredAuditLogger auditLogger) {        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.channelRegistry = channelRegistry;
        this.attemptRecorder = attemptRecorder;
        // 退款业务指标（refund.*）由拥有退款生命周期的 refund-service 记录；支付侧退款尝试
        // 仅是渠道透传（不迁移支付领域状态），故此处只注入、不记录。
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 兼容构造（Feature 028 / FR-036 / SC-012）：保留既有「单通道」签名，
     * 内部包装为「单通道注册表 + 仓储兼作写入口」——既有测试零改动。
     *
     * <p>{@code attemptRepository} 同时充当 {@link ChannelAttemptRecorder}（内存实现两者兼备）。</p>
     */
    public PaymentRefundService(PaymentRepository paymentRepository,
                                PaymentAttemptRepository attemptRepository,
                                PaymentChannel singleChannel,
                                BusinessMetrics metrics,
                                StructuredAuditLogger auditLogger) {
        this(paymentRepository, attemptRepository,
                new SingleChannelRegistry(singleChannel),
                ChannelAttemptRecorders.of(attemptRepository), metrics, auditLogger);
    }

    public PaymentAmountQueryResponse queryAmount(PaymentAmountQueryRequest request) {
        Payment payment = paymentRepository.findByPaymentNo(request.paymentNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + request.paymentNo()));
        return new PaymentAmountQueryResponse(payment.getPaymentNo(), payment.getOrderNo(), payment.getUserId(),
                payment.getAmountMinor(), payment.getCurrencyCode(), payment.getStatus().name());
    }

    public RefundAttemptResponse refund(RefundAttemptRequest request) {
        Payment payment = paymentRepository.findByPaymentNo(request.paymentNo())
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + request.paymentNo()));
        if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "payment not refundable in status " + payment.getStatus());
        }
        // INV-6 / FR-024：渠道取自被退支付单的生效支付渠道，经注册表解析——不调 Router、不硬编码
        PaymentAttempt effective = resolveEffectiveAttempt(request.paymentNo());
        String channelCode = effective.getChannelCode();
        PaymentChannel channel = channelRegistry.resolve(channelCode);
        // spec 030 / FR-153（T64）：退款是<b>反向路径</b>——没有入站 HTTP 请求，染色 ThreadLocal 为空。
        // 必须用<b>落库的模态</b>（生效 attempt 行的 channelMode）包裹渠道调用；
        // 不包裹的话，沙箱支付单的退款会退化成走 mock 渠道——原渠道的钱根本没退。
        ChannelResult result = DyeContext.callWith(effective.getChannelMode(),
                () -> channel.refund(new RefundRequest(request.paymentNo(), request.refundNo(),
                        request.amountMinor(), request.currencyCode(), channelCode)));
        // D2（spec 018）：REFUND 尝试记所属支付单金额（payment 金额），而非退款金额（request.amountMinor）
        recordRefundChannelAttempt(payment, request, channelCode, result);
        String mappedStatus = switch (result.status()) {
            case SUCCESS -> "SUCCEEDED";
            case FAILURE -> "FAILED";
            case UNKNOWN -> "UNKNOWN";
        };
        return new RefundAttemptResponse(request.refundNo(), mappedStatus, result.channelReference());
    }

    /**
     * 解析被退支付单的<b>生效支付渠道</b>（FR-005）：
     * 该 {@code payment_no} 下 {@code attempt_type=PAYMENT} 且状态 {@code SUCCEEDED} 的
     * 那一行的 {@code channel_code}。
     *
     * <p>查不到 → {@code INTERNAL_ERROR}，<b>绝不静默回落 MOCK</b>——这是数据异常，
     * 静默回落会让退款流向错误渠道（资金安全红线）。</p>
     */
    private String resolveEffectiveChannelCode(String paymentNo) {
        return resolveEffectiveAttempt(paymentNo).getChannelCode();
    }

    /**
     * 解析被退支付单的<b>生效支付 attempt</b>（FR-005 + spec 030 / FR-153）。
     *
     * <p>除 {@code channel_code} 外还带出该行的<b>模态</b>（{@code extra_json} 的
     * {@code channelMode}）——退款要用它包裹渠道调用。<b>渠道与模态必须同源</b>：
     * 用 A 渠道的实现配 B 渠道的模态，等于拿错渠道的协议去问另一个渠道。</p>
     *
     * <p><b>确定性排序（同 FR-272）</b>：多条 SUCCEEDED attempt 时恒取 {@code id} 最小者，
     * 避免「退到哪个渠道」取决于数据库返回顺序。</p>
     */
    private PaymentAttempt resolveEffectiveAttempt(String paymentNo) {
        List<PaymentAttempt> attempts = attemptRepository.findByPaymentNo(paymentNo);
        return attempts.stream()
                .filter(a -> PaymentAttempt.TYPE_PAYMENT.equals(a.getAttemptType()))
                .filter(a -> a.getStatus() == PaymentAttemptStatus.SUCCEEDED)
                .filter(a -> a.getChannelCode() != null && !a.getChannelCode().isBlank())
                .sorted(java.util.Comparator.comparing(PaymentAttempt::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .findFirst()
                .orElseThrow(() -> BizException.of(ErrorCodes.INTERNAL_ERROR,
                        "no effective payment channel for payment " + paymentNo
                                + " (refund must not guess or fall back to a default channel)"));
    }

    /**
     * 退款渠道尝试落库（Feature 016 / FR-017 第②步 / N4 修复）：复用 {@code payment_attempts}
     * 落一条 REFUND 类型尝试（payment_no 关联 + channel_reference = 渠道退款流水号，唯一约束兜底），
     * 对账退款事实据此取得真实渠道退款流水号（废弃 {@code refund-{id}} 合成引用）。
     * 渠道引用重复（重试/幂等重放）按唯一约束吸收，不影响退款结果回传。
     *
     * <p><b>Feature 028 / FR-005</b>：{@code channelCode} 取自生效支付渠道（消除 S5 硬编码
     * {@code "mock"}）；attempt 写操作经渠道层端口 {@link ChannelAttemptRecorder}（INV-5）。</p>
     */
    private void recordRefundChannelAttempt(Payment payment, RefundAttemptRequest request,
                                            String channelCode, ChannelResult result) {
        // FIX-4：退款尝试的「创建 + 收敛 + 落库」整体归渠道层端口（INV-5 写入口唯一）。
        // payment 层只交出资金口径（D2：所属支付单金额，而非退款金额）与权威渠道结果，
        // 不再自己 new / converge / save attempt，也不再自己 catch 重复键——
        // 「重复键意味着什么」需要渠道引用的语义，只有渠道层能答（无条件吸收正是 F5 的伪装来源）。
        attemptRecorder.recordRefundAttempt(request.paymentNo(), channelCode,
                payment.getAmountMinor(), payment.getCurrencyCode(), result);
    }
}
