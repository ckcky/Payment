package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.channelgateway.application.ChannelGateway;
import com.payment.common.dto.channel.CallbackUrls;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.channelgateway.application.PaymentChannel;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.channelgateway.application.RouteContext;
import com.payment.payment.application.reliability.PaymentRetryService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.mq.PaymentEventPublisher;
import java.util.Set;
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
    /**
     * 渠道网关门面（spec 037 / FR-007 / INV-1）：本类<b>只</b>经它接触渠道网关——
     * {@code ChannelRegistry} / {@code ChannelRouter} 是网关域私有实现，不得出现在资金动作域。
     */
    private final ChannelGateway channelGateway;
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
                                     ChannelGateway channelGateway,
                                     ObjectProvider<PaymentEventPublisher> mqProvider) {
        this.paymentRepository = paymentRepository;
        this.paymentPersistence = paymentPersistence;
        this.retryService = retryService;
        this.orderGateway = orderGateway;
        this.ledgerGateway = ledgerGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
        this.channelGateway = channelGateway;
        this.mq = mqProvider == null ? null : mqProvider.getIfAvailable();
    }

    /** 兼容构造（不接账本）：空记账网关（测试/账本未接入场景）；无注册表、无 Router 的门面。 */
    public PaymentApplicationService(PaymentRepository paymentRepository,
                                     PaymentPersistence paymentPersistence,
                                     PaymentRetryService retryService,
                                     OrderGateway orderGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this(paymentRepository, paymentPersistence, retryService, orderGateway,
                facts -> {
                }, metrics, auditLogger, ChannelGateway.none(), null);
    }

    /**
     * 账本兼容构造（Feature 028 / FR-036）：保留既有 7 参重载签名，内部包装为
     * 「<b>单通道注册表 + 恒等路由</b>」——{@code route()} 恒返回该通道 code，
     * 且显式渠道校验恒通过。既有测试零改动（SC-012）。
     *
     * <p>spec 037 / T4：垫片从本类迁入网关域的 {@link ChannelGateway#ofSingleChannel}——
     * 资金动作域不再自己实现注册表。</p>
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
                metrics, auditLogger, ChannelGateway.ofSingleChannel(singleChannel), null);
    }

    /**
     * 支付（spec 041）：应用服务主入口，流程固定为五步。
     *
     * <ol>
     *   <li><b>参数校验</b>：字段级由 Controller 的 {@code @Valid} 完成，此处做命令构造与
     *       领域不变量（金额 &gt; 0 由 {@link Payment} 聚合构造器保证）；</li>
     *   <li><b>建单</b>：选路 → 落库（幂等键兜底，重复请求返回首次结果）；</li>
     *   <li><b>调用下游</b>：经渠道网关扣款（事务之外，通信失败内联退避重放）；</li>
     *   <li><b>处理返回结果</b>：凭证 / 终态分支 → 落库 → 记账 → 通知 order；</li>
     *   <li><b>返回结果</b>：{@link PayResult}（支付单 + 权威渠道码 + 可选付款链接）。</li>
     * </ol>
     *
     * <p>幂等以数据库唯一约束 {@code uk_payments_idempotency_key} 兜底（非进程内内存登记）：
     * 先按幂等键回查，未命中则插入；并发/重启后的重复插入撞唯一约束，捕获后回查返回首次结果。
     * 持久化（插入待处理 / 应用渠道结果落库）各自为独立短事务（见 {@link PaymentPersistence}），
     * 而外部渠道调用与跨服务履约 RPC 均运行在事务之外，
     * 避免 DB 连接被网络调用长期占用（雪崩风险）。履约 RPC 失败不回滚支付成功事实。</p>
     */
    public PayResult pay(CreatePaymentRequest request) {
        return pay(CreatePaymentCommand.from(request));
    }

    /**
     * 支付（命令形态，五步流水线本体）。
     *
     * <p>本方法只做<b>编排</b>：每一步的实现细节都在对应私有方法里，
     * 任何一步的分支都不得回写到本方法（spec 041：编排方法 MUST 可逐行读出五步）。</p>
     */
    public PayResult pay(CreatePaymentCommand cmd) {
        // 1 参数校验与命令构造
        CreatePaymentCommand command = requireCommand(cmd);

        // 2 建单：选路 → 落库 → 幂等判定
        String routedChannelCode = resolveChannelCode(command);
        PaymentPersistence.PendingPayment pending = paymentPersistence.insertPending(command, routedChannelCode);
        String channelCode = effectiveChannelCode(pending, routedChannelCode);
        if (!pending.created()) {
            metrics.counter("payment.duplicate", 1.0, "module", MODULE);
            return new PayResult(pending.payment(), channelCode, null);
        }
        metrics.counter("payment.initiated", 1.0, "module", MODULE);

        // 3 调用下游：渠道扣款
        PaymentRetryService.RetryOutcome outcome = chargeChannel(pending.payment(), command, routedChannelCode);

        // 4 处理返回结果 + 5 返回结果
        return applyChannelResult(pending, routedChannelCode, outcome);
    }

    /**
     * 支付意图创建（兼容入口，spec 041 前既有形态）。
     *
     * <p>保留只为既有测试与旧脚本零改动；新代码一律用 {@link #pay(CreatePaymentCommand)}，
     * 它额外回带权威渠道码与付款链接。</p>
     */
    public Payment createPaymentIntent(CreatePaymentCommand cmd) {
        return pay(cmd).payment();
    }

    /** 步骤 1：命令非空校验（字段级校验由 {@code @Valid} 完成，此处只守入口不变量）。 */
    private CreatePaymentCommand requireCommand(CreatePaymentCommand cmd) {
        if (cmd == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "create payment command must not be null");
        }
        return cmd;
    }

    /**
     * 步骤 3：调用渠道扣款。
     *
     * <p>本方法只负责「把平台意图翻译成渠道契约并调用」，<b>不做任何「要不要调用」的判断</b>——
     * 那是渠道网关域的派发策略（spec 041 / FR-021）。</p>
     */
    private PaymentRetryService.RetryOutcome chargeChannel(Payment payment, CreatePaymentCommand cmd,
                                                           String channelCode) {
        // spec 030 本期：编排层不推导默认场景（tasks Q7 / 零回归）。
        // 场景校验 MUST 在调 charge **之前**（INV-8：不静默降级）。
        PaymentScene scene = null;
        validateSceneIfPresent(scene, channelCode);

        // spec 030 / FR-103 + Q5：回调地址来自**配置单值**（不按单动态拼）。未配置 ⇒ null，
        // 沙箱链路会在 charge 之前 400（INV-8：不静默降级）；mock 链路不读该字段，零回归。
        CallbackUrls callbackUrls = configuredCallbackUrls();

        // 渠道扣款在事务之外执行；通信失败在本次请求内联退避重放（ADR-0012/0013 修订），
        // 重试期间不落库，最终结果与重试次数一次性写入。
        return retryService.chargeWithRetry(new ChargeRequest(payment.getPaymentNo(),
                cmd.amountMinor(), cmd.currencyCode(), channelCode,
                scene, null, callbackUrls, null, null, null, null, cmd.orderNo()));
    }

    /** 步骤 4：应用渠道返回结果（凭证 / 终态两条分支），步骤 5 在此一并返回。 */
    private PayResult applyChannelResult(PaymentPersistence.PendingPayment pending,
                                         String channelCode,
                                         PaymentRetryService.RetryOutcome outcome) {
        ChannelResult result = outcome.result();

        // spec 030 / FR-115（T30）· INV-6：凭证非空 ⇒ 渠道**仅受理**、买家尚未付款 ⇒
        // **不调 applyAndPersist**，payment 停在 PROCESSING（不记账、不通知 order）。
        // 钱还没到却走成功收敛路径＝把「已受理」当成「已收款」，是资金事故。
        if (result.hasCredential()) {
            // 只记凭证种类，绝不打印 payload（INV-2：凭证 MUST NOT 进明文日志）
            LOGGER.info("channel accepted with credential, payment stays PROCESSING paymentNo={} kind={}",
                    pending.payment().getPaymentNo(), result.credential().kind());
            metrics.counter("payment.awaiting_buyer", 1.0, "module", MODULE);
            return new PayResult(pending.payment(), channelCode, result.credential().payload());
        }

        // 应用渠道结果并落库（独立短事务，含本次实际重试次数）
        PaymentPersistence.AppliedPayment applied = paymentPersistence.applyAndPersist(
                pending.payment().getId(), pending.attempt().getId(), result, outcome.retries());
        if (applied.changed()) {
            recordTransition(applied.payment(), applied.fromStatus(), result);
        }
        if (applied.changed() && result.status() == ChannelResult.Status.SUCCESS) {
            notifyOrderSucceeded(applied.payment());
            postLedgerCapture(applied.payment(), channelCode);
        }
        return new PayResult(applied.payment(), channelCode, null);
    }

    /**
     * 步骤 4a：支付成功 → 通知 order（Feature 016 / ADR-0054）。
     *
     * <p>同步 charge 路径不再直调履约——通知统一由 orderGateway 异步于本请求之外完成
     * （见 {@code PaymentResultProcessor}）；此处仅编排自身支付指令。</p>
     */
    private void notifyOrderSucceeded(Payment payment) {
        // spec 029 / T28、FR-201：payment.succeeded 改事务消息（点对点 → order），
        // 替代同步 OrderGateway.notifyPaymentSucceeded。SC-1：同步通知点清零。
        if (mq != null) {
            try {
                mq.publishPaymentSucceeded(PaymentResultApplier.toSucceededRequest(payment));
            } catch (RuntimeException ex) {
                // commit 失败不回滚支付成功事实（INV-1）；半消息由回查按 payments 表补投
                metrics.counter("payment.order_notify_failed", 1.0, "module", MODULE);
            }
            return;
        }
        try {
            orderGateway.notifyPaymentSucceeded(PaymentResultApplier.toSucceededRequest(payment));
        } catch (RuntimeException ignored) {
            // 订单回写失败不得回滚支付成功事实（订单侧幂等 + 后续对账收敛）。
        }
    }

    /** 步骤 4b：已确认的支付成功 → 账本复式记账（Feature 004 / FR-006）。 */
    private void postLedgerCapture(Payment payment, String channelCode) {
        // 记账失败不回滚支付成功事实，进入待记账由对账兜底（ADR-0009，手续费 MVP 计 0）。
        //
        // spec 031（FR-101 / ADR-0077）：改传**已确认财务事实**（Financial Fact）；
        // 幂等键由账本按 {eventType}:{sourceId} 派生（原则 10），两条路径同 paymentNo 同键。
        ledgerGateway.postPaymentCapture(new LedgerPostingGateway.PaymentCaptureFacts(
                payment.getPaymentNo(), payment.getMerchantId(),
                channelCode, payment.getAmountMinor(), 0L, 0L,
                payment.getCurrencyCode()));
    }

    /**
     * 本次生效的渠道码：幂等重复时以库内已记录的 attempt 渠道为准（首次那笔的真实渠道）。
     *
     * <p>回带权威渠道码的原因（Feature 028 / FR-027）：渠道身份记在
     * {@code payment_attempts.channel_code}（{@code payments} 表无该列），调用方若回显
     * 自己请求里的 {@code channelCode}，在「不指定渠道、由 Router 选路」的场景下会得到
     * {@code null}——收银台与排障都会拿到错误信息。</p>
     */
    private String effectiveChannelCode(PaymentPersistence.PendingPayment pending, String routedChannelCode) {
        return pending.attempt() == null || pending.attempt().getChannelCode() == null
                ? routedChannelCode
                : pending.attempt().getChannelCode();
    }

    /**
     * spec 030 / FR-110（T31）：场景校验——{@code scene != null} 且渠道未声明支持
     * ⇒ {@code 400 INVALID_ARGUMENT}；{@code scene == null} <b>不校验</b>（INV-8 / 零回归）。
     *
     * <p>门面 {@link ChannelGateway#supportedScenes} 返回 {@code null} 时跳过校验：那是既有兼容
     * 构造路径（无注册表），且本期编排层不传场景——此处 MUST NOT 因此抛 NPE 破坏既有测试（SC-A-02）。</p>
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
        if (scene == null) {
            return;
        }
        // spec 037 / T4：能力查询经门面；返回 null 即「无注册表」（既有兼容构造路径）⇒ 跳过校验。
        // 判据与改造前 channelRegistry == null 逐字等价（NFR-2）。
        Set<PaymentScene> supported = channelGateway.supportedScenes(channelCode);
        if (supported == null) {
            return;
        }
        if (!supported.contains(scene)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "channel " + channelCode + " does not support scene " + scene
                            + "; supported: " + supported);
        }
    }

    /**
     * 支付结果（spec 041）：支付单 + **权威渠道码** + **可选付款链接**。
     *
     * <p>{@code channelCode} 是权威渠道口径——显式指定时即该值，自动选路时为 Router 决策结果，
     * 幂等重复时取首次落库的 attempt 渠道。调用方（HTTP 响应 / 收银台）一律用它，
     * 不得回显请求里的原始 {@code channelCode}。</p>
     *
     * <p>{@code payUrl} 非空即「渠道已受理、买家尚未付款」（INV-6）：
     * 调用方把它透传给前端引导买家付款，<b>不落库</b>（INV-2）。
     * 它的来源由渠道网关决定（真实渠道凭证或演示收银台），本域不感知、也不得自行拼装。</p>
     */
    public record PayResult(Payment payment, String channelCode, String payUrl) {
    }

    /**
     * 解析本次支付的最终渠道码（FR-023 / FR-028）。
     *
     * <p>无 Router（兼容构造）时回落到「用调用方给的 code，缺省 MOCK」——保持旧行为；
     * 有 Router 时走路由（显式优先 / 自动选路 / 可用性判定）。两条分支都由门面
     * {@link ChannelGateway#route} 承载（spec 037 / FR-007）。</p>
     */
    private String resolveChannelCode(CreatePaymentCommand cmd) {
        // spec 037 / T4：选路经门面——无 Router 时门面内部回落「用调用方给的 code，缺省 MOCK」，
        // 与改造前本方法的兼容分支逐字等价（NFR-2）。
        return channelGateway.route(
                new RouteContext(cmd.amountMinor(), cmd.currencyCode(), cmd.channelCode()));
    }

    /** 按业务单号查询（对外 GET / 跨服务引用一律用 paymentNo，ADR-0063）。 */
    public Payment getPaymentByNo(String paymentNo) {
        return paymentRepository.findByPaymentNo(paymentNo)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "payment not found: " + paymentNo));
    }

    /**
     * 支付单查询（spec 041 / FR-022）：按 {@code paymentNo} 与 / 或 {@code transactionId} 寻址。
     *
     * <h3>为什么废除「数值 id 或 paymentNo」的双轨</h3>
     * <p>改造前 {@code getPaymentByRef(ref)} 用「字符串是否全数字」猜调用方传的是主键还是业务单号。
     * 这是典型的<b>靠数据形态猜语义</b>：既违反 ADR-0063（跨系统标识一律业务单号、禁止数值 ID），
     * 又让 {@code PM} 前缀单号之外的任何输入都变成一次盲猜。实测 6 个调用点<b>全部</b>传业务单号，
     * 该分支零调用，纯属历史灰度残留。</p>
     *
     * <p>新口径：
     * <ul>
     *   <li>单传 {@code paymentNo} → 按支付单号查；</li>
     *   <li>单传 {@code transactionId} → 按交易单号查（一交易多支付单时返回最近一笔）；</li>
     *   <li>两者都传 → 先按 {@code paymentNo} 查，再校验其 {@code transactionId} 是否一致，
     *       不一致抛 {@code 400 INVALID_ARGUMENT}（不做「忽略其中一个」的静默降级，INV-8）；</li>
     *   <li>两者都空 → 抛 {@code 400 INVALID_ARGUMENT}。</li>
     * </ul>
     */
    public Payment queryPayment(String paymentNo, String transactionId) {
        boolean hasPaymentNo = paymentNo != null && !paymentNo.isBlank();
        boolean hasTransactionId = transactionId != null && !transactionId.isBlank();
        if (!hasPaymentNo && !hasTransactionId) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "paymentNo or transactionId is required");
        }
        if (!hasPaymentNo) {
            return paymentRepository.findByTransactionId(transactionId)
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND,
                            "payment not found for transactionId: " + transactionId));
        }
        Payment payment = getPaymentByNo(paymentNo);
        if (hasTransactionId && !transactionId.equals(payment.getTransactionId())) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "paymentNo " + paymentNo + " does not belong to transactionId " + transactionId);
        }
        return payment;
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
