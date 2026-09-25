package com.payment.payment.application.channel.spi;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.common.dto.channel.PaymentScene;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 渠道插件的<b>模板方法基类</b>（渠道插件化内核 / SPI-06）——微内核的「内核」本体。
 *
 * <h2>主流程由内核固定，渠道只填差异</h2>
 * 三个入口方法（{@link #charge} / {@link #refund} / {@link #queryStatus}）一律
 * {@code final}——<b>子类不得覆写</b>。每一笔渠道交互都必须按下面四步走，顺序不可变：
 *
 * <ol>
 *   <li><b>能力前置校验</b>：本次请求的 {@code scene} 不在 {@code descriptor().supportedScenes()}
 *       内 ⇒ {@code 400 INVALID_ARGUMENT}。在调用渠道之前就拒绝，而不是拿到一个
 *       打不开的付款链接再让用户发现。</li>
 *   <li><b>模态门控</b>：染色 {@code SANDBOX} 而本插件真实模式未启用 ⇒
 *       {@code 400 INVALID_ARGUMENT}，<b>绝不静默回落 mock</b>（FR-241 / INV-8）。</li>
 *   <li><b>模态分派</b>：{@code MOCK} → 内核统一 mock 语义；{@code SANDBOX} → 子类原语
 *       {@code doRealXxx}。</li>
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
 * <h3>为什么 MOCK 模态也由内核统一提供（而不是各渠道各写一份）</h3>
 * spec 022 定义了<b>全渠道一致</b>的确定性故障注入（金额尾数 11→超时 / 12→无结论 /
 * 15→业务拒绝），E2E 断言依赖这个一致性。把它放在内核，新渠道<b>自动获得</b>同一口径——
 * 若让各渠道自己实现 mock，就会出现「同一个金额在 A 渠道超时、在 B 渠道成功」，
 * 全链路测试随即失去确定性。</p>
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
     * 运行级前缀（跨重启唯一）。
     *
     * <p>与 {@code AbstractMockChannelAdapter} 的同名设计同理：mock 引用若不带运行级前缀，
     * 重启后会与库里残留行撞 {@code uk_attempts_channel_reference} 导致建单失败。
     * <b>每实例独立</b>，绝不共享。</p>
     */
    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final AtomicLong mockSeq = new AtomicLong();

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
     * MOCK 扣款（spec 022 / T429 确定性故障注入，口径与 {@code AbstractMockChannelAdapter} 一致）。
     *
     * <p>子类<b>不得覆写</b>——覆写就会破坏「全渠道同一金额触发同一结果」这条 E2E 前提。
     * 若某渠道确实需要特殊 mock 行为，那是个需要 ADR 的决策，不是一次覆写。</p>
     */
    protected ChannelResult doMockCharge(ChargeRequest request) {
        ChannelResult injected = requestLevelInjection(request);
        if (injected != null) {
            return injected;
        }
        return ChannelResult.success(mockReference("mock-chg"));
    }

    /** MOCK 退款：同步成功（与既有 mock 渠道同口径）。 */
    protected ChannelResult doMockRefund(RefundRequest request) {
        return ChannelResult.success(mockReference("mock-rfd"));
    }

    /** MOCK 查询：回带既有渠道引用当作成功（使「下单→查询」在 mock 下自洽）。 */
    protected ChannelResult doMockQuery(QueryStatusRequest request) {
        return ChannelResult.success(request.channelTransactionId());
    }

    /**
     * 请求级确定性触发（spec 022 / D7 / T429）：按 {@code amount_minor % 100} 尾数注入，
     * 未命中返回 {@code null}：
     * <ul>
     *   <li>{@code 11} → 渠道超时（通信失败，可重试）；</li>
     *   <li>{@code 12} → 渠道无业务结论（回调丢失形态，进 UNKNOWN 等查询收敛）；</li>
     *   <li>{@code 15} → 渠道明确业务拒绝（硬失败，不重试）。</li>
     * </ul>
     * <b>与 {@code AbstractMockChannelAdapter#requestLevelInjection} 逐字同口径</b>——
     * 两处必须一起改，否则跨渠道 E2E 断言会失去确定性。
     */
    private ChannelResult requestLevelInjection(ChargeRequest request) {
        return switch ((int) (request.amountMinor() % 100)) {
            case 11 -> ChannelResult.timeout("e2e injected timeout: amount tail 11");
            case 12 -> ChannelResult.businessUnknown("e2e injected inconclusive: amount tail 12 (callback lost)");
            case 15 -> ChannelResult.businessFailure(mockReference("mock-chg"),
                    "e2e injected decline: amount tail 15");
            default -> null;
        };
    }

    private String mockReference(String kind) {
        return channelCode().toLowerCase() + "-" + kind + "-" + runId + "-" + mockSeq.incrementAndGet();
    }

    // ===================== 主流程的固定步骤 =====================

    /**
     * ① 能力前置校验（FR-109 / FR-131）：{@code scene != null} 且不在支持集合内 ⇒ 400。
     *
     * <p>{@code scene == null} 不校验（INV-8 / 零回归）：既有调用点大量不传场景，
     * 一刀切校验会把它们全部打断。</p>
     */
    private void requireSceneSupported(PaymentScene scene) {
        if (scene == null) {
            return;
        }
        Set<PaymentScene> supported = descriptor().supportedScenes();
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
