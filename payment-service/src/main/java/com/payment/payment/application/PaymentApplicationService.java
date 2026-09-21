package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.CallbackUrls;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChannelRouter;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.PayCredential;
import com.payment.payment.application.channel.PaymentScene;
import com.payment.payment.application.channel.RouteContext;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.mq.PaymentEventPublisher;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PaymentApplicationService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentPersistence paymentPersistence;
    private final PaymentRetryService retryService;
    private final OrderGateway orderGateway;
    private final LedgerPostingGateway ledgerGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;
    private final ChannelRouter channelRouter;
    private final ChannelRegistry channelRegistry;
    /**
     * spec 030 / FR-103 + tasks Q5 裁决「配置单值」：渠道**异步回调（notify）**地址。
     * 空 = 未配置（沙箱链路会 400，见 {@link #configuredCallbackUrls()}）。
     */
    @org.springframework.beans.factory.annotation.Value("${payment.channel.notify-url:}")
    private String channelNotifyUrl;
    /** 买家付款后页面跳回地址（**非**资金事实，可空）。 */
    @org.springframework.beans.factory.annotation.Value("${payment.channel.return-url:}")
    private String channelReturnUrl;
    /** spec 029 / FR-201 / T28：`mq.enabled=true` 时存在，走事务消息；否则回落同步 Feign（FR-306）。 */
    private final PaymentEventPublisher mq;

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
                                     ChannelRegistry channelRegistry,
                                     ObjectProvider<PaymentEventPublisher> mqProvider) {
        this.paymentRepository = paymentRepository;
        this.paymentPersistence = paymentPersistence;
        this.retryService = retryService;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.channelRouter = channelRouter;
        this.channelRegistry = channelRegistry;
        this.mq = mqProvider == null ? null : mqProvider.getIfAvailable();
    }

    /** 兼容构造（不接账本）：空记账网关（测试/账本未接入场景）；选路用恒等路由。 */
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     OrderGateway orderGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this(paymentRepository, paymentPersistence, retryService, orderGateway,
                facts -> {
                }, metrics, auditLogger, null, null, null);
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
                metrics, auditLogger, identityRouter(singleChannel), singleChannelRegistry(singleChannel), null);
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

        // spec 030 / FR-110（T31）：构造 ChargeRequest 时填充扩展字段。
        // 兼容构造器只给 5 参，此处按 12 参全参构造（场景 / 商品 / 回调地址 / 有效期 / 付款人）。
        // 场景校验 MUST 在调 charge **之前**（INV-8：不静默降级）。
        PaymentScene scene = null; // spec 030 本期：编排层不推导默认场景（tasks Q7 / 零回归）
        validateSceneIfPresent(scene, routedChannelCode);

        // spec 030 / FR-103 + Q5：回调地址来自**配置单值**（不按单动态拼）。未配置 ⇒ null，
        // 沙箱链路会在 charge 之前 400（INV-8：不静默降级）；mock 链路不读该字段，零回归。
        CallbackUrls callbackUrls = configuredCallbackUrls();

        // 渠道扣款在事务之外执行；通信失败在本次请求内联退避重放（ADR-0012/0013 修订），
        // 重试期间不落库，最终结果与重试次数一次性写入。
        PaymentRetryService.RetryOutcome outcome = retryService.chargeWithRetry(
                new ChargeRequest(pending.payment().getPaymentNo(),
                        pending.attempt().getId(), cmd.amountMinor(), cmd.currencyCode(),
                        routedChannelCode,
                        scene, null, callbackUrls, null, null, null, null));
        ChannelResult result = outcome.result();

        // spec 030 / FR-115（T30）· INV-6：凭证非空 ⇒ 渠道**仅受理**、买家尚未付款 ⇒
        // **不调 applyAndPersist**，payment 停在 PROCESSING（不记账、不通知 order）。
        // 钱还没到却走成功收敛路径＝把「已受理」当成「已收款」，是资金事故。
        if (result.hasCredential()) {
            // 只记凭证种类，绝不打印 payload（INV-2：凭证 MUST NOT 进明文日志）
            LOGGER.info("channel accepted with credential, payment stays PROCESSING paymentNo={} kind={}",
                    pending.payment().getPaymentNo(), result.credential().kind());
            metrics.counter("payment.awaiting_buyer", 1.0, "module", MODULE);
            return new RoutedPayment(pending.payment(), routedChannelCode, result.credential());
        }

        // 应用渠道结果并落库（独立短事务，含本次实际重试次数）
        PaymentPersistence.AppliedPayment applied = paymentPersistence.applyAndPersist(
                pending.payment().getId(), pending.attempt().getId(), result, outcome.retries());
        if (applied.changed()) {
            recordTransition(applied.payment(), applied.fromStatus(), result);
        }

        // Feature 016（ADR-0054）：同步 charge 路径不再直调履约——支付成功通知统一由
        // orderGateway 异步于本请求之外完成（见 PaymentResultProcessor）；此处仅编排自身支付指令。
        if (applied.changed() && result.status() == ChannelResult.Status.SUCCESS) {
            // spec 029 / T28、FR-201：payment.succeeded 改事务消息（点对点 → order），
            // 替代同步 OrderGateway.notifyPaymentSucceeded。SC-1：同步通知点清零。
            if (mq != null) {
                try {
                    mq.publishPaymentSucceeded(
                            PaymentResultApplier.toSucceededRequest(applied.payment()));
                } catch (RuntimeException ex) {
                    // commit 失败不回滚支付成功事实（INV-1）；半消息由回查按 payments 表补投
                    metrics.counter("payment.order_notify_failed", 1.0, "module", MODULE);
                }
            } else {
                try {
                    orderGateway.notifyPaymentSucceeded(
                            PaymentResultApplier.toSucceededRequest(applied.payment()));
                } catch (RuntimeException ignored) {
                    // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）。
                }
            }
            // 已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）；
            // 记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009，手续费 MVP 计 0）。
            //
            // spec 031（FR-101 / ADR-0077）：改传**已确认财务事实**（Financial Fact）；
            // 幂等键由账本按 {eventType}:{sourceId} 派生（原则 10），两条路径同 paymentNo 同键。
            ledgerGateway.postPaymentCapture(new LedgerPostingGateway.PaymentCaptureFacts(
                    applied.payment().getPaymentNo(), applied.payment().getMerchantId(),
                    routedChannelCode, applied.payment().getAmountMinor(), 0L, 0L,
                    applied.payment().getCurrencyCode()));
        }
        return new RoutedPayment(applied.payment(), routedChannelCode, result.credential());
    }

    /**
     * spec 030 / FR-110（T31）：场景校验——{@code scene != null} 且渠道未声明支持
     * ⇒ {@code 400 INVALID_ARGUMENT}；{@code scene == null} <b>不校验</b>（INV-8 / 零回归）。
     *
     * <p>{@code channelRegistry == null} 时跳过校验：那是既有兼容构造路径（无注册表），
     * 且本期编排层不传场景——此处 MUST NOT 因此抛 NPE 破坏既有测试（SC-A-02）。</p>
     */
    /**
     * spec 030 / FR-103 + tasks Q5 裁决「配置单值」：把配置的 notify / return 地址装进
     * {@link CallbackUrls}，供渠道下单时透传。
     *
     * <p><b>未配置 notify-url ⇒ 返回 {@code null}</b>：与 spec 030 之前的行为逐字节一致
     * （零回归；mock 链路不读该字段）。沙箱链路会在 {@code charge} <b>之前</b>因缺
     * {@code notifyUrl} 抛 {@code 400 INVALID_ARGUMENT}——<b>不静默降级</b>（INV-8）：
     * 没有异步通知就拿不到资金事实，而页面跳回（returnUrl）MUST NOT 驱动支付状态。</p>
     *
     * <p>字段由 Spring {@code @Value} 注入；不经过 Spring 容器时（既有测试直接 new）
     * 保持 {@code null} ⇒ 本方法返回 {@code null}，既有断言全部不变。</p>
     */
    private CallbackUrls configuredCallbackUrls() {
        if (channelNotifyUrl == null || channelNotifyUrl.isBlank()) {
            return null;
        }
        String returnUrl = (channelReturnUrl == null || channelReturnUrl.isBlank())
                ? null : channelReturnUrl;
        return new CallbackUrls(channelNotifyUrl, returnUrl);
    }

    private void validateSceneIfPresent(PaymentScene scene, String channelCode) {
        if (scene == null || channelRegistry == null) {
            return;
        }
        PaymentChannel channel = channelRegistry.resolve(channelCode);
        if (!channel.supportedScenes().contains(scene)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "channel " + channelCode + " does not support scene " + scene
                            + "; supported: " + channel.supportedScenes());
        }
    }

    /**
     * 建单结果：支付单 + **最终生效的渠道码**（FR-027）+ **可选付款凭证**（spec 030 / FR-114）。
     *
     * <p>{@code channelCode} 是权威渠道口径——显式指定时即该值，自动选路时为 Router 决策结果，
     * 幂等重复时取首次落库的 attempt 渠道。调用方（HTTP 响应 / 收银台 payUrl）一律用它，
     * 不得回显请求里的原始 {@code channelCode}。</p>
     *
     * <p>{@code credential} 非空即「渠道已受理、买家尚未付款」（INV-6）：
     * 调用方应把它透传给前端引导买家付款，<b>不落库</b>（INV-2）。</p>
     */
    public record RoutedPayment(Payment payment, String channelCode, PayCredential credential) {

        /** 兼容构造（无凭证）：既有调用点与测试零改动。 */
        public RoutedPayment(Payment payment, String channelCode) {
            this(payment, channelCode, null);
        }
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
