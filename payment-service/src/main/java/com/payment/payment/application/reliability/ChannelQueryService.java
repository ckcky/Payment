package com.payment.payment.application.reliability;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.channelgateway.application.ChannelRegistry;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.PaymentChannel;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.SingleChannelRegistry;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.Comparator;
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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChannelQueryService.class);

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
                new SingleChannelRegistry(singleChannel),
                resolution, config, metrics);
        this.fallbackChannel = singleChannel;
    }

    /** 兼容路径的渠道兜底（仅当 attempt 仓储为 null 时使用，即既有单测场景）。 */
    private PaymentChannel fallbackChannel;

    /**
     * 毒丸一次性告警守卫（spec 034 / 诊断②）：无 recorded attempt 的脏数据单每次扫描都会
     * 在 resolveRecordedTarget 抛错——修复前会让整轮 abort 且每 15s 刷一次栈；
     * 现单条隔离，同一单只 warn 一次（进程内去重，计数器照常累计）。
     */
    private final java.util.Map<String, Boolean> poisonPillWarned = new java.util.concurrent.ConcurrentHashMap<>();

    /** 主动查询一轮：返回本轮收敛为终态的支付数量。 */
    public int queryRound() {
        int converged = 0;
        for (Payment payment : paymentRepository.findByStatus(PaymentStatus.UNKNOWN)) {
            // spec 034 / T14：扫描期 UNKNOWN 年龄分桶观测（label 仅 bucket+module，无单号）
            if (payment.getEnteredUnknownAt() != null) {
                metrics.counter("payment_unknown_age", 1.0, "module", MODULE,
                        "bucket", bucketOf(java.time.Duration.between(
                                payment.getEnteredUnknownAt(), java.time.Instant.now())));
            }
            if (payment.getQueryAttempts() >= config.getQueryMaxAttempts()) {
                continue; // 已达上限：停止自动查询，转人工/对账（FR-003 / spec 场景3）
            }
            try {
                if (queryOnce(payment)) {
                    converged++;
                }
            } catch (RuntimeException ex) {
                // spec 034 / 诊断②：单条毒丸不中断整轮、不再每 15s 刷栈——一次性 warn + 计数
                metrics.counter("payment.query_poison_pill", 1.0, "module", MODULE);
                if (poisonPillWarned.put(payment.getPaymentNo(), Boolean.TRUE) == null) {
                    log.warn("主动查询单条毒丸跳过（每单仅告警一次）paymentNo={} reason={}",
                            payment.getPaymentNo(), ex.getMessage());
                }
            }
        }
        return converged;
    }

    /**
     * 单笔 UNKNOWN 支付的一次主动查询（spec 034 / 诊断② 从 queryRound 抽出）：
     * 记录尝试、按落库渠道查询、非 UNKNOWN 结果交收敛服务。
     *
     * @return true = 本轮收敛为终态
     */
    private boolean queryOnce(Payment payment) {
        RecordedTarget target = resolveRecordedTarget(payment);
        payment.recordQueryAttempt();
        paymentRepository.save(payment);
        // spec 030 / FR-270（T60）：查询必须带**渠道交易号**（attempt.channel_reference）。
        // 修复前传的是平台侧 transactionId——拿平台号去问渠道，渠道定位不到原交易，
        // 主动查询因此永远收敛不了（C-12 / S21）。
        QueryStatusRequest queryRequest = new QueryStatusRequest(payment.getPaymentNo(),
                payment.getTransactionId(), payment.getIdempotencyKey(), target.channelReference());
        // spec 030 / FR-271（T61）：反向路径没有入站请求，ThreadLocal 为空——
        // 必须用**落库的模态**包裹渠道调用，否则沙箱单会退化成 mock 查询。
        // spec 035 §5.2 / A-03：渠道出站调用进 channel_request{channelCode,result}；
        // 渠道契约（PaymentChannel javadoc）规定超时/断连/不完整响应 MUST 映射 UNKNOWN，
        // 故 UNKNOWN 结果与通信异常均计入 channel_timeout{channelCode}（唯一可观测代理，目录已注明）。
        String channelCode = target.channel().channelCode();
        ChannelResult result;
        try {
            result = DyeContext.callWith(target.mode(), () -> {
                metrics.counter("payment.query", 1.0, "module", MODULE);
                return target.channel().queryStatus(queryRequest);
            });
        } catch (RuntimeException ex) {
            metrics.counter("channel_request", 1.0, "channelCode", channelCode, "result", "exception");
            metrics.counter("channel_timeout", 1.0, "channelCode", channelCode);
            throw ex;
        }
        metrics.counter("channel_request", 1.0, "channelCode", channelCode,
                "result", switch (result.status()) {
                    case SUCCESS -> "success";
                    case FAILURE -> "failed";
                    case UNKNOWN -> "unknown";
                });
        if (result.status() == ChannelResult.Status.UNKNOWN) {
            metrics.counter("channel_timeout", 1.0, "channelCode", channelCode);
        }
        if (result.status() != ChannelResult.Status.UNKNOWN) {
            return resolution.resolve(String.valueOf(payment.getId()), result);
        }
        if (payment.getQueryAttempts() >= config.getQueryMaxAttempts()) {
            metrics.counter("payment.query_exhausted", 1.0, "module", MODULE);
        }
        return false;
    }

    /** UNKNOWN 年龄分桶（spec 034 §7.2 / T14 目录）：0_5m / 5_30m / 30m_24h / gt_24h。 */
    public static String bucketOf(java.time.Duration age) {
        if (age.isNegative()) {
            age = java.time.Duration.ZERO; // 时钟回拨防御
        }
        if (age.compareTo(java.time.Duration.ofMinutes(5)) < 0) {
            return "0_5m";
        }
        if (age.compareTo(java.time.Duration.ofMinutes(30)) < 0) {
            return "5_30m";
        }
        if (age.compareTo(java.time.Duration.ofHours(24)) < 0) {
            return "30m_24h";
        }
        return "gt_24h";
    }

    /**
     * 反向路径的调用目标（spec 030 / FR-272）：渠道实现 + 该 attempt 记录的模态 + 渠道交易号。
     *
     * <p>三者<b>必须同源</b>——都取自同一条 attempt 行。分头取会出现「用 A 渠道的实现、
     * 配 B 渠道的模态、查 C 渠道的交易号」这类张冠李戴。</p>
     */
    private record RecordedTarget(PaymentChannel channel, DyeMode mode, String channelReference) {
    }

    /**
     * 解析该支付单<b>已记录</b>的渠道（INV-6）：取 {@code attempt_type=PAYMENT} 的那一行的
     * {@code channel_code}，经注册表取实现；同时带出该行的模态与渠道交易号（FR-272）。
     *
     * <p><b>确定性排序（FR-272，修 S22）</b>：改造前这里是无 {@code ORDER BY} 的
     * {@code findFirst()}——同一支付单有多条 attempt 时，选到哪条<b>取决于数据库返回顺序</b>，
     * 于是「查哪个渠道」这个决定变得不可复现。现按 {@code id} 升序取第一条（最先建的那次交互）。</p>
     *
     * <p><b>模态取自同一行</b>（FR-154）：{@code extra_json} 的 {@code channelMode} 键，
     * 经 {@link PaymentAttempt#getChannelMode()} 读取（四类坏数据一律 {@link DyeMode#MOCK}）。</p>
     *
     * <p>找不到记录行时抛 {@code INTERNAL_ERROR}（FR-273）——不回落默认渠道，因为
     * 「不知道这单走的哪个渠道」时去问任何一个渠道都是无意义甚至有害的
     * （可能污染别的渠道的事实）。</p>
     */
    private RecordedTarget resolveRecordedTarget(Payment payment) {
        // 兼容路径（既有单测未接 attempt 仓储）：直接用单通道——语义等同改造前的单例注入
        if (attemptRepository == null) {
            return new RecordedTarget(fallbackChannel, DyeMode.MOCK, null);
        }
        PaymentAttempt attempt = attemptRepository.findByPaymentNo(payment.getPaymentNo()).stream()
                .filter(a -> PaymentAttempt.TYPE_PAYMENT.equals(a.getAttemptType()))
                .filter(a -> a.getStatus() != PaymentAttemptStatus.PENDING)
                .filter(a -> a.getChannelCode() != null && !a.getChannelCode().isBlank())
                // 确定性排序：同一支付单多条 attempt 时恒取 id 最小（最先建）的那条
                .sorted(Comparator.comparing(PaymentAttempt::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElseThrow(() -> com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                        "no recorded channel for payment " + payment.getPaymentNo()
                                + "; query must not pick an arbitrary channel"));
        return new RecordedTarget(channelRegistry.resolve(attempt.getChannelCode()),
                attempt.getChannelMode(), attempt.getChannelReference());
    }
}
