package com.payment.payment.application.reliability;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;

/**
 * UNKNOWN 主动查询收敛（spec US2 / ADR-0003）：周期性扫描 UNKNOWN 支付，向渠道查询权威状态，
 * 并据此收敛为成功/失败，且只触发一次下游动作。
 *
 * <p>行为要点：
 * <ul>
 *   <li>仅对未达查询上限（{@link ReliabilityConfig#getQueryMaxAttempts()}）的 UNKNOWN 支付查询；
 *       达到上限后停止自动查询，转人工/对账（spec 场景3）。</li>
 *   <li>渠道返回 SUCCESS/FAILURE 视为权威结果，复用 {@link PaymentUnknownResolutionService#resolve}
 *       收敛（终态冲突被吸收，保证「最多一次」下游动作，FR-004）。</li>
 *   <li>渠道返回 UNKNOWN（仍不明确）不猜成败，保持 UNKNOWN 等待后续查询/回调/对账。</li>
 * </ul>
 * 幂等与「最多一次」由支付状态机 + 收敛服务保证；乐观锁由仓储保护并发。</p>
 *
 * <p><b>反向路径按记录解析（Feature 028 / FR-024 / INV-6）</b>：查询渠道 MUST 取自该支付单
 * 已记录的 {@code payment_attempts.channel_code}，经 {@link ChannelRegistry} 解析——
 * <b>绝不调 Router</b>。若用 Router 重新选路，UNKNOWN 支付可能被换个渠道去问，
 * 那等于去问一个从未受理过这笔交易的渠道（必然查不到，或更糟：查到别人的交易）。</p>
 */
@Service
public class ChannelQueryService {

    private static final String MODULE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ChannelRegistry channelRegistry;
    private final PaymentUnknownResolutionService resolution;
    private final ReliabilityConfig config;
    private final BusinessMetrics metrics;

    /** 生产主构造：Spring 必须确定地选它（另有测试用兼容构造，故显式标注）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ChannelQueryService(PaymentRepository paymentRepository,
                               PaymentAttemptRepository attemptRepository,
                               ChannelRegistry channelRegistry,
                               PaymentUnknownResolutionService resolution,
                               ReliabilityConfig config,
                               BusinessMetrics metrics) {
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.channelRegistry = channelRegistry;
        this.resolution = resolution;
        this.config = config;
        this.metrics = metrics;
    }

    /**
     * 兼容构造（Feature 028 / FR-036 / SC-012）：保留既有「单通道」签名（4 参 + 指标），
     * 内部包装为「单通道注册表」——既有测试零改动。
     *
     * <p>attempt 仓储取不到时用「恒返回该单通道」的退化注册表：既有单测不落 attempt 行，
     * 语义与改造前（直接持有 channel 单例）完全一致。</p>
     */
    public ChannelQueryService(PaymentRepository paymentRepository,
                               PaymentChannel singleChannel,
                               PaymentUnknownResolutionService resolution,
                               ReliabilityConfig config,
                               BusinessMetrics metrics) {
        this(paymentRepository, null,
                new com.payment.payment.infra.channel.SingleChannelRegistry(singleChannel),
                resolution, config, metrics);
        this.fallbackChannel = singleChannel;
    }

    /** 兼容路径的渠道兜底（仅当 attempt 仓储为 null 时使用，即既有单测场景）。 */
    private PaymentChannel fallbackChannel;

    /** 主动查询一轮：返回本轮收敛为终态的支付数量。 */
    public int queryRound() {
        int converged = 0;
        for (Payment payment : paymentRepository.findByStatus(PaymentStatus.UNKNOWN)) {
            if (payment.getQueryAttempts() >= config.getQueryMaxAttempts()) {
                continue; // 已达上限：停止自动查询，转人工/对账（FR-003 / spec 场景3）
            }
            PaymentChannel channel = resolveRecordedChannel(payment);
            payment.recordQueryAttempt();
            paymentRepository.save(payment);
            ChannelResult result = channel.queryStatus(
                    new QueryStatusRequest(payment.getPaymentNo(), payment.getTransactionId(), payment.getIdempotencyKey()));
            metrics.counter("payment.query", 1.0, "module", MODULE);
            if (result.status() != ChannelResult.Status.UNKNOWN) {
                if (resolution.resolve(String.valueOf(payment.getId()), result)) {
                    converged++;
                }
            } else if (payment.getQueryAttempts() >= config.getQueryMaxAttempts()) {
                metrics.counter("payment.query_exhausted", 1.0, "module", MODULE);
            }
        }
        return converged;
    }

    /**
     * 解析该支付单<b>已记录</b>的渠道（INV-6）：取 {@code attempt_type=PAYMENT} 的那一行的
     * {@code channel_code}，经注册表取实现。
     *
     * <p>找不到记录行时抛 {@code INTERNAL_ERROR}——不回落默认渠道，因为「不知道这单走的哪个渠道」
     * 时去问任何一个渠道都是无意义甚至有害的（可能污染别的渠道的事实）。</p>
     */
    private PaymentChannel resolveRecordedChannel(Payment payment) {
        // 兼容路径（既有单测未接 attempt 仓储）：直接用单通道——语义等同改造前的单例注入
        if (attemptRepository == null) {
            return fallbackChannel;
        }
        String channelCode = attemptRepository.findByPaymentNo(payment.getPaymentNo()).stream()
                .filter(a -> PaymentAttempt.TYPE_PAYMENT.equals(a.getAttemptType()))
                .filter(a -> a.getStatus() != PaymentAttemptStatus.PENDING)
                .map(PaymentAttempt::getChannelCode)
                .filter(code -> code != null && !code.isBlank())
                .findFirst()
                .orElseThrow(() -> com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                        "no recorded channel for payment " + payment.getPaymentNo()
                                + "; query must not pick an arbitrary channel"));
        return channelRegistry.resolve(channelCode);
    }
}
