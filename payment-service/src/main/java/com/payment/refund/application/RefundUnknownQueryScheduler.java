package com.payment.refund.application;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.trace.TraceContext;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.reliability.ReliabilityConfig;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundRepository;
import com.payment.refund.domain.RefundStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款 UNKNOWN 主动查询收敛（spec 034 §7.3 F-4 / T12）：周期性扫描 UNKNOWN 退款，
 * 按<b>已记录</b>的 REFUND 尝试行（渠道 + 模态 + 渠道退款流水号）向渠道查询权威状态，
 * 命中即经 {@link RefundResultProcessor} 统一收敛（与 resolve 同构，绝不自行臆断成败）。
 *
 * <p><b>政策完全复用 payment 侧</b>（spec §7.3：不新造配置）：查询窗口 =
 * {@code queryMaxAttempts × queryIntervalMs}（默认 5×15s=75s）。</p>
 *
 * <p><b>时间窗推导尝试次数（plan §3.1，不新增 schema）</b>：UNKNOWN 期间
 * {@code refunds.updated_at} 稳定（无写路径触碰），以其为 age 事实源——
 * {@code age ≤ 窗口} 内每轮发起查询（第 n 次窗口边界的越界沿只触发一次
 * {@code refund.query_exhausted} 计数，{@code age < 窗口 + interval} 守卫防重放）；
 * 越窗后停止自动查询，转 resolve / 对账兜底（对称 payment 侧 queryMaxAttempts）。</p>
 *
 * <p><b>观测（spec 034 / T14）</b>：每轮扫描发射 {@code refund_unknown_age}
 * 计数（bucket = 0_5m / 5_30m / 30m_24h / gt_24h，label 仅 bucket+module，不带 refundNo——
 * 035 高基数政策）；入口 {@code runWithNewTrace}（诊断①）。</p>
 */
@Component
public class RefundUnknownQueryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefundUnknownQueryScheduler.class);
    private static final String MODULE = "refund";

    private final RefundRepository refundRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final ChannelRegistry channelRegistry;
    private final RefundResultProcessor resultProcessor;
    private final ReliabilityConfig config;
    private final BusinessMetrics metrics;

    /**
     * query_exhausted 一次性发射守卫（越界沿只计一次）：进程内存态，重启后重放一次计数
     * 可接受（计数是告警信号不是资金事实，plan §3.1「守卫防重放」的口径即进程内去重）。
     */
    private final Map<String, Boolean> exhaustedReported = new ConcurrentHashMap<>();

    public RefundUnknownQueryScheduler(RefundRepository refundRepository,
                                       PaymentAttemptRepository attemptRepository,
                                       ChannelRegistry channelRegistry,
                                       RefundResultProcessor resultProcessor,
                                       ReliabilityConfig config,
                                       BusinessMetrics metrics) {
        this.refundRepository = refundRepository;
        this.attemptRepository = attemptRepository;
        this.channelRegistry = channelRegistry;
        this.resultProcessor = resultProcessor;
        this.config = config;
        this.metrics = metrics;
    }

    /** 调度入口（spec 034 / 诊断①）：新 traceId 包裹整轮扫描。 */
    @Scheduled(fixedDelayString = "${payment.reliability.query-interval-ms:15000}")
    public void run() {
        TraceContext.runWithNewTrace(() -> scanRound(Instant.now()));
    }

    /** 扫描一轮：返回本轮收敛为终态的退款数量（测试与观测用）。 */
    public int scanRound(Instant now) {
        Duration window = config.getQueryIntervalMs() > 0
                ? Duration.ofMillis(config.getQueryMaxAttempts() * (long) config.getQueryIntervalMs())
                : Duration.ofSeconds(75);
        Duration exhaustEdge = window.plus(Duration.ofMillis(config.getQueryIntervalMs()));
        int converged = 0;
        for (Refund refund : refundRepository.findByStatus(RefundStatus.UNKNOWN)) {
            emitAgeBucket(refund, now);
            Instant persistedAt = refund.getUpdatedAt();
            Duration age = persistedAt == null ? Duration.ZERO : Duration.between(persistedAt, now);
            if (age.isNegative()) {
                age = Duration.ZERO; // 时钟回拨防御：age 不为负
            }
            if (age.compareTo(window) > 0) {
                // 越窗：停扫该单；只在「刚越窗」的边界沿报告一次耗尽（守卫 age < 窗口 + interval）
                if (age.compareTo(exhaustEdge) < 0
                        && exhaustedReported.put(refund.getRefundNo(), Boolean.TRUE) == null) {
                    metrics.counter("refund.query_exhausted", 1.0, "module", MODULE);
                    log.warn("退款主动查询窗口耗尽，停扫转人工/对账兜底 refundNo={} ageSeconds={}",
                            refund.getRefundNo(), age.toSeconds());
                }
                continue;
            }
            try {
                if (queryOnce(refund)) {
                    converged++;
                }
            } catch (RuntimeException ex) {
                // 单条毒丸不中断整轮（对称诊断②纪律）：warn + 计数，下一轮继续
                metrics.counter("refund.query_scan_error", 1.0, "module", MODULE);
                log.warn("退款主动查询单条失败（不中断整轮）refundNo={} reason={}",
                        refund.getRefundNo(), ex.getMessage());
            }
        }
        return converged;
    }

    /**
     * 对一笔 UNKNOWN 退款发起一次渠道权威查询：命中非 UNKNOWN 结果 → 经
     * {@link RefundResultProcessor}（Source.CHANNEL_QUERY）统一收敛；渠道仍无结论 →
     * 保持 UNKNOWN（不猜成败，R-3）。
     */
    private boolean queryOnce(Refund refund) {
        RecordedTarget target = resolveRecordedTarget(refund);
        QueryStatusRequest queryRequest = new QueryStatusRequest(refund.getPaymentNo(),
                refund.getTransactionNo(), refund.getIdempotencyKey(), target.channelReference());
        // spec 030 / FR-271：调度线程无入站模态上下文，用**落库的模态**包裹渠道调用
        ChannelResult result = DyeContext.callWith(target.mode(), () -> {
            metrics.counter("refund.query", 1.0, "module", MODULE);
            return target.channel().queryStatus(queryRequest);
        });
        if (result.status() == ChannelResult.Status.UNKNOWN) {
            return false; // 渠道无记录 / 仍不明确：保持 UNKNOWN
        }
        Refund convergedRefund = resultProcessor.apply(refund, result, RefundResultProcessor.Source.CHANNEL_QUERY);
        log.info("退款主动查询收敛 refundNo={} status={} channelRef={}",
                refund.getRefundNo(), convergedRefund.getStatus(), result.channelReference());
        return true;
    }

    /**
     * 反向路径目标（INV-6 / spec 030 FR-272）：REFUND 尝试行记录的渠道实现 + 模态 + 渠道退款流水号，
     * 三者同源——<b>绝不调 Router 重新选路</b>（退款换渠道 = 钱退错地方）。
     *
     * <p>与 {@code ChannelQueryService#resolveRecordedTarget} 同型：取
     * {@code attempt_type=REFUND}、非 PENDING、有渠道码的行，按 id 升序取第一条（最先建）。
     * 找不到记录行时抛 {@code INTERNAL_ERROR}（FR-273：不知道走哪个渠道的退款，问任何渠道都无意义），
     * 由 {@link #scanRound} 的单条容错吸收并计数。</p>
     */
    private RecordedTarget resolveRecordedTarget(Refund refund) {
        PaymentAttempt attempt = attemptRepository.findByPaymentNo(refund.getPaymentNo()).stream()
                .filter(a -> PaymentAttempt.TYPE_REFUND.equals(a.getAttemptType()))
                .filter(a -> a.getStatus() != PaymentAttemptStatus.PENDING)
                .filter(a -> a.getChannelCode() != null && !a.getChannelCode().isBlank())
                .sorted(Comparator.comparing(PaymentAttempt::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst()
                .orElseThrow(() -> com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.INTERNAL_ERROR,
                        "no recorded refund channel for payment " + refund.getPaymentNo()
                                + "; refund query must not pick an arbitrary channel"));
        return new RecordedTarget(channelRegistry.resolve(attempt.getChannelCode()),
                attempt.getChannelMode(), attempt.getChannelReference());
    }

    /** 反向路径调用目标：渠道实现 + 落库模态 + 渠道退款流水号（必须同源，见上）。 */
    private record RecordedTarget(PaymentChannel channel, com.payment.common.core.dye.DyeMode mode,
                                  String channelReference) {
    }

    /** 扫描期年龄分桶观测（spec 034 §7.2 / T14；label 仅 bucket+module，无单号）。 */
    private void emitAgeBucket(Refund refund, Instant now) {
        Instant persistedAt = refund.getUpdatedAt();
        Duration age = persistedAt == null ? Duration.ZERO
                : Duration.between(persistedAt, now).truncatedTo(ChronoUnit.SECONDS);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        metrics.counter("refund_unknown_age", 1.0, "module", MODULE,
                "bucket", bucketOf(age));
    }

    /** 年龄分桶（spec §7.2 目录）：0_5m / 5_30m / 30m_24h / gt_24h。 */
    static String bucketOf(Duration age) {
        if (age.compareTo(Duration.ofMinutes(5)) < 0) {
            return "0_5m";
        }
        if (age.compareTo(Duration.ofMinutes(30)) < 0) {
            return "5_30m";
        }
        if (age.compareTo(Duration.ofHours(24)) < 0) {
            return "30m_24h";
        }
        return "gt_24h";
    }
}
