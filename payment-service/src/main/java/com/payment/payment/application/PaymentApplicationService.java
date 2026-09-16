package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChannelRouter;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.RouteContext;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 支付意图创建（T037）：幂等受理、创建支付与尝试、调用渠道并应用结果。
 *
 * <p>幂等键以 {@code payment:create} 作用域登记：先于扣款登记（避免并发重复重复扣款），
 * 重复请求返回首次结果。</p>
 *
 * <p><b>选路（Feature 028 / FR-023，ADR-0073）</b>：选路 MUST 发生在<b>建单之前</b>——
 * 以最终 {@code channelCode} 参与幂等键构造与支付单落库，<b>不得先落库再改写</b>
 * （否则出现「建单用 A、请求发往 B」的不一致窗口）。</p>
 */
@Service
public class PaymentApplicationService {

    private static final String MODULE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentPersistence paymentPersistence;
    private final PaymentRetryService retryService;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;
    private final ChannelRouter channelRouter;
    private final ChannelRegistry channelRegistry;

    /** 生产主构造：Spring 必须唯一确定地选它（另有测试用兼容构造，故显式标注）。 */
    @Autowired
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     OrderGateway orderGateway,
                                     LedgerPostingGateway ledgerGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger,
                                     ChannelRouter channelRouter,
                                     ChannelRegistry channelRegistry) {
        this.paymentRepository = paymentRepository;
        this.paymentPersistence = paymentPersistence;
        this.retryService = retryService;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.channelRouter = channelRouter;
        this.channelRegistry = channelRegistry;
    }

    /** 兼容构造（不接账本）：空记账网关（测试/账本未接入场景）；选路用恒等路由。 */
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     OrderGateway orderGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this(paymentRepository, paymentPersistence, retryService, orderGateway,
                (key, paymentId, amountMinor, feeMinor, currencyCode) -> {
                }, metrics, auditLogger, null, null);
    }

    /**
     * 账本兼容构造（Feature 028 / FR-036）：保留既有 7 参重载签名，内部包装为
     * 「<b>单通道注册表 + 恒等路由</b>」——{@code route()} 恒返回该通道 code，
     * 且显式渠道校验恒通过。既有测试零改动（SC-012）。
     */
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     OrderGateway orderGateway,
                                     LedgerPostingGateway ledgerGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger,
                                     PaymentChannel singleChannel) {
        this(paymentRepository, paymentPersistence, retryService, orderGateway, ledgerGateway,
                metrics, auditLogger, identityRouter(singleChannel), singleChannelRegistry(singleChannel));
    }

    /** 恒等路由：不管上下文如何，恒返回该通道 code（FR-036 兼容垫片）。 */
    private static ChannelRouter identityRouter(PaymentChannel channel) {
        return context -> channel.channelCode();
    }

    /** 单通道注册表：只认该通道，未知码抛 INVALID_ARGUMENT（FR-036 兼容垫片）。 */
    private static ChannelRegistry singleChannelRegistry(PaymentChannel channel) {
        String code = channel.channelCode().toUpperCase();
        Set<String> codes = new TreeSet<>();
        codes.add(code);
        return new ChannelRegistry() {
            @Override
            public PaymentChannel resolve(String channelCode) {
                if (channelCode == null || !code.equals(channelCode.trim().toUpperCase())) {
                    throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                            "unknown channelCode '" + channelCode + "'; registered channels: " + codes);
                }
                return channel;
            }

            @Override
            public Set<String> registeredCodes() {
                return codes;
            }
        };
    }

    /**
     * 支付意图创建：幂等受理、创建支付与尝试、调用渠道并应用结果。
     *
     * <p>幂等以数据库唯一约束 {@code uk_payments_idempotency_key} 兜底（非进程内内存登记）：
     * 先按幂等键回查，未命中则插入；并发/重启后的重复插入撞唯一约束，捕获后回查返回首次结果。
     * 持久化（插入待处理 / 应用渠道结果落库）各自为独立短事务（见 {@link PaymentPersistence}），
     * 而外部渠道调用 {@code channel.charge} 与跨服务履约 RPC 均运行在事务之外，
     * 避免 DB 连接被网络调用长期占用（雪崩风险）。履约 RPC 失败不回滚支付成功事实。</p>
     */
    public Payment createPaymentIntent(CreatePaymentCommand cmd) {
        return createPaymentIntent(cmd, false);
    }

    /**
     * 支付意图创建（ADR-0048 修订版重载）：{@code deferChannel=true} 时跳过渠道内联同步调用，
     * Payment 停留 PROCESSING 等待收银台回调驱动状态迁移（mock-cashier.enabled=true 演示路径）。
     *
     * <p>既有语义零变化：默认 {@code deferChannel=false} 与原方法完全等价；幂等重复
     * （返回首次结果）与渠道调用无关，不受 defer 影响。</p>
     */
    public Payment createPaymentIntent(CreatePaymentCommand cmd, boolean deferChannel) {
        return createPaymentIntentWithRouting(cmd, deferChannel).payment();
    }

    /**
     * 支付意图创建，并**回带最终路由渠道码**（Feature 028 / FR-027）。
     *
     * <p>为什么需要回带：渠道身份记在 {@code payment_attempts.channel_code}（{@code payments}
     * 表无该列），调用方若回显自己请求里的 {@code channelCode}，在「不指定渠道、由 Router 选路」
     * 的场景下会得到 {@code null}——收银台与排障都会拿到错误信息。故由本方法统一给出权威值。</p>
     */
    public RoutedPayment createPaymentIntentWithRouting(CreatePaymentCommand cmd, boolean deferChannel) {
        // FR-023：选路在建单之前——最终 code 同时用于幂等键与 attempt 落库
        String routedChannelCode = resolveChannelCode(cmd);

        PaymentPersistence.PendingPayment pending = paymentPersistence.insertPending(cmd, routedChannelCode);
        // 幂等重复（返回首次结果）时，以库内已记录的 attempt 渠道为准（首次那笔的真实渠道）
        String effectiveChannelCode = pending.attempt() == null || pending.attempt().getChannelCode() == null
                ? routedChannelCode
                : pending.attempt().getChannelCode();
        if (!pending.created()) {
            metrics.counter("payment.duplicate", 1.0, "module", MODULE);
            return new RoutedPayment(pending.payment(), effectiveChannelCode);
        }
        metrics.counter("payment.initiated", 1.0, "module", MODULE);

        if (deferChannel) {
            // 收银台路径：不调渠道、不落渠道结果。超时（30s）后由 TimeoutScanner 转 UNKNOWN，
            // 主动查询不收敛即停留 UNKNOWN —— 演示「点了不回调」「不猜成败落账」。
            metrics.counter("payment.deferred_to_cashier", 1.0, "module", MODULE);
            return new RoutedPayment(pending.payment(), effectiveChannelCode);
        }

        // 渠道扣款在事务之外执行；通信失败在本次请求内联退避重放（ADR-0012/0013 修订），
        // 重试期间不落库，最终结果与重试次数一次性写入。
        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(
                new ChargeRequest(pending.payment().getPaymentNo(),
                        pending.attempt().getId(), cmd.amountMinor(), cmd.currencyCode(),
                        routedChannelCode));
        ChannelResult result = outcome.result();

        // 应用渠道结果并落库（独立短事务，含本次实际重试次数）
        PaymentPersistence.AppliedPayment applied = paymentPersistence.applyAndPersist(
                pending.payment().getId(), pending.attempt().getId(), result, outcome.retries());
        if (applied.changed()) {
            recordTransition(applied.payment(), applied.fromStatus(), result);
        }

        // Feature 016（ADR-0054）：同步 charge 路径不再直调履约——支付成功通知统一由
        // orderGateway 异步于本请求之外完成（见 PaymentResultProcessor）；此处仅编排自身支付指令。
        if (applied.changed() && result.status() == ChannelResult.Status.SUCCESS) {
            try {
                orderGateway.notifyPaymentSucceeded(
                        PaymentResultApplier.toSucceededRequest(applied.payment()));
            } catch (RuntimeException ignored) {
                // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）。
            }
            // 已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）；
            // 记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009，手续费 MVP 计 0）。
            ledgerGateway.postPaymentCapture(applied.payment().getIdempotencyKey(),
                    applied.payment().getPaymentNo(), applied.payment().getAmountMinor(), 0L,
                    applied.payment().getCurrencyCode());
        }
        return new RoutedPayment(applied.payment(), routedChannelCode);
    }

    /**
     * 建单结果：支付单 + **最终生效的渠道码**（FR-027）。
     *
     * <p>{@code channelCode} 是权威渠道口径——显式指定时即该值，自动选路时为 Router 决策结果，
     * 幂等重复时取首次落库的 attempt 渠道。调用方（HTTP 响应 / 收银台 payUrl）一律用它，
     * 不得回显请求里的原始 {@code channelCode}。</p>
     */
    public record RoutedPayment(Payment payment, String channelCode) {
    }

    /**
     * 解析本次支付的最终渠道码（FR-023 / FR-028）。
     *
     * <p>无 Router（兼容构造）时回落到「用调用方给的 code，缺省 MOCK」——保持旧行为；
     * 有 Router 时走 {@link ChannelRouter#route}（显式优先 / 自动选路 / 可用性判定）。</p>
     */
    private String resolveChannelCode(CreatePaymentCommand cmd) {
        if (channelRouter == null) {
            return cmd.channelCode() == null || cmd.channelCode().isBlank() ? "MOCK" : cmd.channelCode();
        }
        return channelRouter.route(
                new RouteContext(cmd.amountMinor(), cmd.currencyCode(), cmd.channelCode()));
    }

    public Payment getPayment(Long id) {
        return requirePayment(id);
    }

    /** 按业务单号查询（对外 GET / 跨服务引用一律用 paymentNo，ADR-0063）。 */
    public Payment getPaymentByNo(String paymentNo) {
        return paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentNo));
    }

    /** 兼容寻址：数值按 id、否则按 paymentNo（演示页灰度期双轨）。 */
    public Payment getPaymentByRef(String ref) {
        return ref.chars().allMatch(Character::isDigit)
                ? getPayment(Long.parseLong(ref))
                : getPaymentByNo(ref);
    }

    private Payment requirePayment(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + id));
    }

    /** 支付真正迁移到终态/未知后记录业务指标与资金审计（fire-and-forget，不改变控制流）。 */
    private void recordTransition(Payment payment, PaymentStatus fromStatus, ChannelResult result) {
        String action = switch (result.status()) {
            case SUCCESS -> "payment.succeeded";
            case FAILURE -> "payment.failed";
            case UNKNOWN -> "payment.unknown";
        };
        PaymentStatus toStatus = PaymentResultApplier.terminalStatusOf(result);
        metrics.counter(action, 1.0, "module", MODULE);
        auditLogger.audit(action, payment.getIdempotencyKey(), payment.getAmountMinor(),
                payment.getCurrencyCode(), fromStatus.name(), toStatus.name(), "payment",
                String.valueOf(payment.getId()));
    }
}
