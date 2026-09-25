package com.payment.channelgateway.application.spi;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.rpc.TransportCode;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.application.ChargeRequest;
import com.payment.common.dto.channel.ChannelRefundNotified;
import com.payment.common.dto.channel.ChannelRefundStatus;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.channelgateway.application.QueryStatusRequest;
import com.payment.channelgateway.application.RefundRequest;
import com.payment.payment.application.PaymentNotifyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 渠道插件的<b>模板方法基类</b>（渠道插件化内核 / SPI-06）——微内核的「内核」本体，
 * 也是<b>全部渠道唯一的基类</b>（spec 037 / T6 / FR-014 迁移后）。
 *
 * <h2>主流程由内核固定，渠道只填差异</h2>
 * 三个入口方法（{@link #charge} / {@link #refund} / {@link #queryStatus}）一律
 * {@code final}——<b>子类不得覆写</b>。每一笔渠道交互都必须按下面四步走，顺序不可变：
 *
 * <ol>
 *   <li><b>能力前置校验</b>：本次请求的 {@code scene} 不在 {@link #supportedScenes()}
 *       内 ⇒ {@code 400 INVALID_ARGUMENT}。在调用渠道之前就拒绝，而不是拿到一个
 *       打不开的付款链接再让用户发现。</li>
 *   <li><b>模态门控</b>：染色 {@code SANDBOX} 而本插件真实模式未启用 ⇒
 *       {@code 400 INVALID_ARGUMENT}，<b>绝不静默回落 mock</b>（FR-241 / INV-8）。</li>
 *   <li><b>模态分派</b>：{@code MOCK} → 内核统一 mock 语义（{@code doMockXxx}）；
 *       {@code SANDBOX} → 子类原语 {@code doRealXxx}。</li>
 *   <li><b>异常兜底</b>：渠道 SDK 抛出的任何运行时异常一律归一化为
 *       {@link ChannelResult.Status#UNKNOWN}（可重试），<b>绝不臆断成败</b>。</li>
 * </ol>
 *
 * <h3>为什么第 ④ 步必须由内核承担</h3>
 * 「渠道超时/抛异常 = 结果未知，不是失败」是资金安全的硬规则（Constitution §V.7）。
 * 若交给每个渠道自己实现，接第 N 家渠道就要正确实现第 N 次——漏一次就是
 * 「网络抖动被记成明确的支付失败，订单被关掉，而渠道其实已经扣款」。
 * 把它固化在 {@code final} 模板里，新渠道<b>想错都错不了</b>。</p>
 *
 * <h3>为什么 MOCK 模态由内核统一提供（而不是各渠道各写一份）</h3>
 * spec 022 定义了<b>全渠道一致</b>的确定性故障注入（金额尾数 11→超时 / 12→无结论 /
 * 15→业务拒绝），E2E 断言依赖这个一致性。把它放在内核，新渠道<b>自动获得</b>同一口径——
 * 若让各渠道自己实现 mock，就会出现「同一个金额在 A 渠道超时、在 B 渠道成功」，
 * 全链路测试随即失去确定性。</p>
 *
 * <h3>MOCK 模态的两层语义（spec 037 / T6 合并）</h3>
 * 迁移前，mock 语义分散在两个基类里：本类只有「尾数注入 + 恒成功」，
 * 而 {@code AbstractMockChannelAdapter} 另有「{@code mock-scenario} 基线场景」与
 * 「退款受理 + 异步推送」。合并后全部收敛到本类，于是 mock 行为是：
 * <ol>
 *   <li><b>请求级注入优先</b>：金额尾数命中 11/12/15 ⇒ 直接产出对应结果（spec 022）；</li>
 *   <li><b>未命中则走基线场景</b>：{@code mock-scenario} 决定 SUCCESS / FAILURE /
 *       TIMEOUT / TRANSPORT_ERROR / BUSINESS_UNKNOWN（ADR-0049）；</li>
 *   <li><b>退款可异步</b>：{@code refund-async=true} 时当场回「已受理」，
 *       延迟后经 {@link PaymentNotifyPort} 推送权威结果（spec 019 / D7）。</li>
 * </ol>
 * 这个顺序不可颠倒——尾数注入是 E2E 确定性的基石，必须先于可配置的基线场景。
 *
 * <h3>子类需要实现的只有三件事</h3>
 * <ul>
 *   <li>{@link #descriptor()} —— 我是谁、我能干什么（自描述）；</li>
 *   <li>{@link #isRealModeEnabled()} —— 真实模式是否可用（配置门控）；</li>
 *   <li>{@code doRealCharge} / {@code doRealRefund} / {@code doRealQuery} —— 三个渠道原语。</li>
 * </ul>
 *
 * @see ChannelPlugin 插件端口
 * @see ChannelPluginFactory 插件工厂 / SPI
 */
public abstract class AbstractChannelPlugin implements ChannelPlugin {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 渠道场景（ADR-0049）：由配置决定，取值必须严格等于枚举名。
     *
     * <p>迁移自 {@code AbstractMockChannelAdapter}——定义在本类，是因为「基线场景」
     * 是 MOCK 模态的语义，而 MOCK 模态由本类统一提供。子类仍可写作
     * {@code XxxAdapter.Scenario.TIMEOUT}（继承的嵌套类型可直接经子类名访问）。</p>
     */
    public enum Scenario {
        /** 通信成功 + 业务成功。 */
        SUCCESS,
        /** 通信成功 + 业务拒绝（硬失败，不重试）。 */
        FAILURE,
        /** 通信超时（{@code TransportCode.TIMEOUT}）：算通信失败，会被内联重试。 */
        TIMEOUT,
        /** 通信层错误（连接被拒 / 5xx）：算通信失败，会被内联重试。 */
        TRANSPORT_ERROR,
        /** 通信成功但渠道未给出业务结论：不重试，进 UNKNOWN 由主动查询收敛。 */
        BUSINESS_UNKNOWN
    }

    private final Scenario scenario;
    private final long httpTimeoutMs;
    /** 退款是否走「受理 + 异步推送」模式（spec 019 / D7，默认开；同步模式保留可配）。 */
    private final boolean refundAsync;
    /** 异步推送延迟（毫秒），模拟渠道受理后的处理耗时。 */
    private final long refundAsyncDelayMs;
    /** 异步推送线程池（守护线程，仅 Mock 演示用；真实渠道为外部系统主动回调）。 */
    private final ScheduledExecutorService refundPusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "channel-refund-pusher");
                t.setDaemon(true);
                return t;
            });
    /** 退款结果推送目标（Spring 装配；纯单元测试可不注入——注入后才有异步推送）。 */
    private volatile PaymentNotifyPort paymentNotifyPort;

    /**
     * 运行级前缀（跨重启唯一）。
     *
     * <p>mock 引用若不带运行级前缀，重启后会与库里残留行撞
     * {@code uk_attempts_channel_reference} 导致建单失败。
     * <b>每实例独立</b>，绝不共享——否则多渠道各自从 1 起，同样撞唯一约束。</p>
     */
    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final AtomicLong refGen = new AtomicLong();
    private ChannelResult queryResult = ChannelResult.businessUnknown("mock query inconclusive");

    // ===================== 构造族（子类按需转发） =====================

    /** 无参构造：默认 SUCCESS 场景、同步退款（供不关心 mock 场景的插件，如 Stripe）。 */
    protected AbstractChannelPlugin() {
        this(Scenario.SUCCESS, 1500L, false, 1000L);
    }

    /** 全参构造（场景对象形态）。 */
    protected AbstractChannelPlugin(Scenario scenario, long httpTimeoutMs,
                                    boolean refundAsync, long refundAsyncDelayMs) {
        this.scenario = scenario == null ? Scenario.SUCCESS : scenario;
        this.httpTimeoutMs = httpTimeoutMs;
        this.refundAsync = refundAsync;
        this.refundAsyncDelayMs = refundAsyncDelayMs;
    }

    /** 场景名 + 超时（测试/演示脚本按场景名构建，同步模式）；坏值 FAIL FAST。 */
    protected AbstractChannelPlugin(String scenario, long httpTimeoutMs) {
        this(parseScenario(scenario), httpTimeoutMs, false, 1000L);
    }

    /** 场景名 + 超时 + 退款形态（Spring 装配用；坏值 FAIL FAST）。 */
    protected AbstractChannelPlugin(String scenario, long httpTimeoutMs,
                                    boolean refundAsync, long refundAsyncDelayMs) {
        this(parseScenario(scenario), httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /** 场景严格枚举解析（坏值 FAIL FAST，附合法取值清单）。 */
    protected static Scenario parseScenario(String raw) {
        String name = raw == null ? "" : raw.trim();
        try {
            return Scenario.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid payment.channel.mock-scenario: '" + raw + "'; expected one of "
                            + Arrays.toString(Scenario.values()), e);
        }
    }

    // ===================== 模板方法：主流程（final，不可覆写） =====================

    /** 扣款主流程。 */
    @Override
    public final ChannelResult charge(ChargeRequest request) {
        requireSceneSupported(request.scene());
        requireRealModeIfSandboxRequested();
        return guard("charge", request.paymentNo(), () -> {
            if (DyeContext.isSandbox()) {
                return doRealCharge(request);
            }
            return doMockCharge(request);
        });
    }

    /** 退款主流程。 */
    @Override
    public final ChannelResult refund(RefundRequest request) {
        // 退款没有「场景」概念：退款是对已发生支付的操作，场景由原支付决定
        requireRealModeIfSandboxRequested();
        return guard("refund", request.paymentNo(), () -> {
            if (DyeContext.isSandbox()) {
                return doRealRefund(request);
            }
            return doMockRefund(request);
        });
    }

    /** 主动查询主流程。 */
    @Override
    public final ChannelResult queryStatus(QueryStatusRequest request) {
        requireRealModeIfSandboxRequested();
        return guard("queryStatus", request.paymentNo(), () -> {
            if (DyeContext.isSandbox()) {
                return doRealQuery(request);
            }
            return doMockQuery(request);
        });
    }

    // ===================== 子类必须填的差异 =====================

    /** 真实模式（沙箱/生产）是否已启用——未启用时染色 SANDBOX 一律硬失败（FR-241）。 */
    protected abstract boolean isRealModeEnabled();

    /** 真实模式扣款原语。 */
    protected abstract ChannelResult doRealCharge(ChargeRequest request);

    /** 真实模式退款原语。 */
    protected abstract ChannelResult doRealRefund(RefundRequest request);

    /** 真实模式查询原语。 */
    protected abstract ChannelResult doRealQuery(QueryStatusRequest request);

    // ===================== 内核统一提供的 MOCK 模态 =====================

    /**
     * MOCK 扣款（spec 022 / T429 确定性故障注入 + ADR-0049 基线场景）。
     *
     * <p>子类<b>不得覆写</b>——覆写就会破坏「全渠道同一金额触发同一结果」这条 E2E 前提。
     * 若某渠道确实需要特殊 mock 行为，那是个需要 ADR 的决策，不是一次覆写。</p>
     */
    protected ChannelResult doMockCharge(ChargeRequest request) {
        String ref = mockReference("ref");
        ChannelResult injected = requestLevelInjection(request, ref);
        if (injected != null) {
            return injected;
        }
        return switch (scenario) {
            case SUCCESS -> ChannelResult.success(ref);
            case FAILURE -> ChannelResult.businessFailure(ref, "mock declined");
            case TIMEOUT -> ChannelResult.timeout(
                    "mock timeout: no response within " + httpTimeoutMs + "ms");
            case TRANSPORT_ERROR -> ChannelResult.transportFailure(
                    TransportCode.CONNECTION_ERROR, "mock connection reset");
            case BUSINESS_UNKNOWN -> ChannelResult.businessUnknown("mock channel still processing");
        };
    }

    /**
     * MOCK 退款：{@code refund-async=true} ⇒ 受理 + 异步推送（spec 019 / D7）；
     * 否则按基线场景同步返回。
     *
     * <p>通信失败类场景（TIMEOUT / TRANSPORT_ERROR）<b>不受异步模式影响</b>：
     * 请求根本没被渠道受理，没有「延迟推送权威结果」可言，当场回传才对。</p>
     */
    protected ChannelResult doMockRefund(RefundRequest request) {
        if (scenario == Scenario.TIMEOUT || scenario == Scenario.TRANSPORT_ERROR) {
            return switch (scenario) {
                case TIMEOUT -> ChannelResult.timeout(
                        "mock refund timeout: no response within " + httpTimeoutMs + "ms");
                default -> ChannelResult.transportFailure(
                        TransportCode.CONNECTION_ERROR, "mock refund connection reset");
            };
        }

        String ref = mockReference("refund-ref");
        if (refundAsync) {
            // 受理 + 异步推送（D7）：当场回「已受理无结论」，延迟后推送权威结果。
            scheduleRefundPush(request.refundNo(), ref);
            return ChannelResult.accepted(ref, "mock refund accepted, awaiting async callback");
        }

        return switch (scenario) {
            case SUCCESS -> ChannelResult.success(ref);
            case FAILURE -> ChannelResult.businessFailure(ref, "mock refund declined");
            default -> ChannelResult.businessUnknown("mock refund still processing");
        };
    }

    /** MOCK 查询：回带既有渠道引用当作成功（使「下单→查询」在 mock 下自洽）。 */
    protected ChannelResult doMockQuery(QueryStatusRequest request) {
        return queryResult;
    }

    /**
     * 请求级确定性触发（spec 022 / D7 / T429）：按 {@code amount_minor % 100} 尾数注入，
     * 未命中返回 {@code null} 走基线场景：
     * <ul>
     *   <li>{@code 11} → 渠道超时（通信失败，可重试）；</li>
     *   <li>{@code 12} → 渠道无业务结论（回调丢失形态，进 UNKNOWN 等查询收敛）；</li>
     *   <li>{@code 15} → 渠道明确业务拒绝（硬失败，不重试）。</li>
     * </ul>
     * <b>本方法由基类唯一实现，子类不得覆写</b>（FR-013：全渠道注入口径必须一致）。
     */
    private ChannelResult requestLevelInjection(ChargeRequest request, String ref) {
        return switch ((int) (request.amountMinor() % 100)) {
            case 11 -> ChannelResult.timeout("e2e injected timeout: amount tail 11");
            case 12 -> ChannelResult.businessUnknown("e2e injected inconclusive: amount tail 12 (callback lost)");
            case 15 -> ChannelResult.businessFailure(ref, "e2e injected decline: amount tail 15");
            default -> null;
        };
    }

    /** 受理后延迟推送权威退款结果（SUCCESS 场景推成功，FAILURE 场景推业务拒绝；无目标则跳过）。 */
    private void scheduleRefundPush(String refundNo, String ref) {
        if (paymentNotifyPort == null) {
            return;
        }
        refundPusher.schedule(() -> {
            PaymentNotifyPort port = paymentNotifyPort;
            if (port == null) {
                return;
            }
            ChannelResult finalResult = scenario == Scenario.FAILURE
                    ? ChannelResult.businessFailure(ref, "mock refund declined")
                    : ChannelResult.success(ref);
            port.onChannelRefundResult(toRefundNotified(refundNo, finalResult));
        }, refundAsyncDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 网关域结果 → 跨域入向事件（spec 037 / T5 / FR-010）。
     *
     * <p>进程内 Mock 的「推送」与真实渠道的 HTTP 回调走同一入向端口（{@link PaymentNotifyPort}），
     * 语义等价、不留双路径。{@code channelNo} 留空：推送这一刻只有 {@code refundNo}
     * （入向寻址键），网关单号的权威值在 {@code payment_attempts.channel_no}。</p>
     */
    private ChannelRefundNotified toRefundNotified(String refundNo, ChannelResult result) {
        return new ChannelRefundNotified(null, refundNo, channelCode(),
                switch (result.status()) {
                    case SUCCESS -> ChannelRefundStatus.SUCCESS;
                    case FAILURE -> ChannelRefundStatus.FAILURE;
                    case UNKNOWN -> ChannelRefundStatus.UNKNOWN;
                },
                result.channelReference(), null, null, result.reason(), Instant.now());
    }

    /** mock 引用：渠道身份小写 + 用途 + 运行级前缀 + 递增序号（跨重启全局唯一）。 */
    private String mockReference(String kind) {
        return channelPrefix() + "-" + kind + "-" + runId + "-" + refGen.incrementAndGet();
    }

    // ===================== 供子类与测试读写的横切状态 =====================

    /** 渠道引用前缀：取自<b>本 Adapter 的身份</b> {@link #channelCode()}（FR-006）。 */
    protected String channelPrefix() {
        String code = channelCode();
        if (code == null || code.isBlank()) {
            return "mock";
        }
        return code.toLowerCase();
    }

    /** 对外（渠道 / 外部系统）HTTP 调用的超时预算，全服务统一 1.5s（ADR-0012 超时口径）。 */
    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    /** 当前场景（供子类与演示开关读写，FR-010）。 */
    protected Scenario getScenario() {
        return scenario;
    }

    protected boolean isRefundAsync() {
        return refundAsync;
    }

    /** 设定主动查询返回结果（默认业务无结论）。 */
    public void setQueryResult(ChannelResult queryResult) {
        this.queryResult = queryResult;
    }

    /**
     * 注入退款结果推送目标（Spring 装配；未注入时异步模式退化为纯受理、不推送）。
     *
     * <p>spec 037 / T5：目标类型由 {@code RefundResultListener}（定义在渠道包）改为
     * {@link PaymentNotifyPort}（Payment 定义 + 实现，FR-012 / INV-2）——
     * 渠道侧不再掌握「退款怎么收敛」的接口定义权。</p>
     */
    @Autowired(required = false)
    public void setPaymentNotifyPort(PaymentNotifyPort paymentNotifyPort) {
        this.paymentNotifyPort = paymentNotifyPort;
    }

    // ===================== 主流程的固定步骤 =====================

    /**
     * ① 能力前置校验（FR-109 / FR-131）：{@code scene != null} 且不在支持集合内 ⇒ 400。
     *
     * <p>取 {@link #supportedScenes()}（而非 {@code descriptor().supportedScenes()}）——
     * 后者是静态声明，而支付宝这类<b>单 Adapter 双模态</b>渠道的能力随模态变化
     * （沙箱按真实开通项收窄，mock 全支持）。用可覆写的方法才能表达这层差异，
     * 否则会在 mock 路径上误拒一个本地模拟本可支持的场景。</p>
     *
     * <p>{@code scene == null} 不校验（INV-8 / 零回归）：既有调用点大量不传场景，
     * 一刀切校验会把它们全部打断。</p>
     */
    private void requireSceneSupported(PaymentScene scene) {
        if (scene == null) {
            return;
        }
        Set<PaymentScene> supported = supportedScenes();
        if (!supported.contains(scene)) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "channel '" + channelCode() + "' does not support scene " + scene
                            + "; supported: " + supported);
        }
    }

    /**
     * ② 模态门控（FR-241 / INV-8）：染色 SANDBOX 而真实模式未启用 ⇒ 400，<b>不静默回落 mock</b>。
     *
     * <p>静默回落最坏的结果是「以为在测沙箱、其实钱根本没进真实渠道」——
     * 演练结论、对账、准入判断会同时失真，而这种失真在账面上看不出来。</p>
     */
    private void requireRealModeIfSandboxRequested() {
        if (DyeContext.isSandbox() && !isRealModeEnabled()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "X-Dye-Tag=SANDBOX requires channel '" + channelCode() + "' real mode enabled; "
                            + "refusing to silently fall back to mock mode");
        }
    }

    /**
     * ④ 异常兜底：把渠道侧的运行时异常归一化为「通信失败 ⇒ 结果未知」。
     *
     * <p><b>{@link BizException} 必须透传</b>：它代表「平台已经明确知道这是错的」
     * （参数不合法、未启用、单据不存在），把它吞成 UNKNOWN 会让明确的错误
     * 变成一笔无限期悬挂的支付。</p>
     */
    private ChannelResult guard(String operation, String paymentNo, Supplier<ChannelResult> primitive) {
        try {
            ChannelResult result = primitive.get();
            if (result == null) {
                // 渠道实现返回 null 是编程错误：宁可记 UNKNOWN 也不能 NPE 把请求线程打穿
                log.error("channel plugin returned null result channel={} op={} paymentNo={}",
                        channelCode(), operation, paymentNo);
                return ChannelResult.businessUnknown(operation + " returned no result");
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            // 資金安全红线（Constitution §V.7）：异常 = 未知，绝不臆断成败。
            // 记 error 但不打印完整堆栈以外的渠道报文（可能含敏感信息，FR-208）
            log.error("channel primitive threw channel={} op={} paymentNo={} ex={}",
                    channelCode(), operation, paymentNo, e.toString());
            return ChannelResult.transportFailure(TransportCode.CONNECTION_ERROR,
                    operation + " failed: " + e.getClass().getSimpleName());
        }
    }
}
