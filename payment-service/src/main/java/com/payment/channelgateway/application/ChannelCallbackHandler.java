package com.payment.channelgateway.application;

import com.payment.channelgateway.application.spi.ChannelCallbackEnvelope;
import com.payment.channelgateway.application.spi.ChannelPlugin;
import com.payment.channelgateway.application.spi.ParsedCallback;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.channel.ChannelPayNotified;
import com.payment.common.dto.channel.ChannelPayStatus;
import com.payment.payment.application.PayNotifyOutcome;
import com.payment.payment.application.PaymentNotifyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 渠道回调的<b>第一层</b>：渠道网关域内的模板方法（spec 037 / T5 / FR-009 / INV-5）。
 *
 * <h2>四步固定流程（{@link #handle} 为 {@code final}，顺序不可变）</h2>
 * <ol>
 *   <li><b>① 工厂 + 策略选插件</b>：按路径上的 {@code channelCode} <b>精确寻址</b>（INV-6）。
 *       绝不重新路由——回调必须回到「当初那笔交互走的渠道」，换渠道 = 钱处理到错的地方。
 *       非插件渠道 / 未声明回调挂载点 ⇒ 直接失败，不静默回落。</li>
 *   <li><b>② 验签</b>（插件钩子）：签名未验证之前，报文里的任何字段都不可信。
 *       失败 ⇒ 403 且<b>不触达</b> Payment 侧（INV-10）。</li>
 *   <li><b>③ 报文转换</b>（插件钩子）：渠道私有报文 → 平台语义。内核<b>不解析任何字段名</b>
 *       ——一旦认识 {@code out_trade_no}，第二家渠道就得改内核。</li>
 *   <li><b>④ 内核统一封装 + 跨域通知</b>：把翻译产物封成 {@code common-dto} 的
 *       {@link ChannelPayNotified}，经 {@link PaymentNotifyPort} 交给 Payment，
 *       再把 Payment 的处理结论转成应答。</li>
 * </ol>
 *
 * <h3>为什么 ② 必须排在 ④ 之前</h3>
 * <p>顺序即防线：把验签排到通知之后（或省掉），任何人都能伪造一条通知让平台把单改成成功。
 * 把 ② 固化在 {@code final} 模板里，新渠道<b>想错都错不了</b>（与 {@code AbstractChannelPlugin}
 * 对三个渠道原语的处置同理）。</p>
 *
 * <h3>关于第 ④ 步「网关单更新」的落点</h3>
 * <p>spec FR-009 的第 ④ 步写作「网关单更新（内核统一）」。本实现里这一步落为
 * <b>「内核统一封装跨域事件 + 跨域通知」</b>：网关侧并不存在一个独立的「网关单」聚合
 * 可以更新——渠道交互的事实载体是 {@code payment_attempts}，而它的收敛入口唯一归属
 * {@code ChannelAttemptRecorder} 端口，且由 Payment 侧的 {@code PaymentResultProcessor}
 * 统一编排。若内核在这里再推一次 attempt 状态，就出现了<b>第二个写入口</b>
 * （两套不变量必然漂移，INV-5 要防的正是这个）。故 ④ 的「内核统一」体现在
 * 「跨域事件的封装口径只有这一处」，而不是重复推进状态。</p>
 *
 * <h3>染色上下文为什么在这里被包裹</h3>
 * <p>回调意味着真实渠道的钱动了，下游收敛必须按 SANDBOX 语义走。这个
 * {@code DyeContext.runWith} 原先散落在回调 Controller 里，现收归网关域
 * （FR-013：模态判定内聚在渠道网关域），Payment 侧因此完全不出现 {@code DyeContext}。</p>
 *
 * @see PaymentNotifyPort 第二层：跨域通知（Payment 定义 + 实现）
 * @see ChannelPlugin#parseCallback ②③ 的插件钩子
 */
@Component
public class ChannelCallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(ChannelCallbackHandler.class);

    private static final String MODULE = "payment";

    private final ChannelRegistry registry;
    private final PaymentNotifyPort notifyPort;
    private final BusinessMetrics metrics;

    public ChannelCallbackHandler(ChannelRegistry registry,
                                  PaymentNotifyPort notifyPort,
                                  BusinessMetrics metrics) {
        this.registry = registry;
        this.notifyPort = notifyPort;
        this.metrics = metrics;
    }

    /**
     * 处理一次渠道回调（模板方法入口，{@code final}：子类/调用方均不得改变步骤顺序）。
     *
     * @param channelCode 回调路径上的渠道码（精确寻址，INV-6）
     * @param envelope    原始信封（内核未做任何渠道语义解析）
     * @return 应答（验签是否通过 + 应答体）
     */
    public final ChannelCallbackAck handle(String channelCode, ChannelCallbackEnvelope envelope) {
        // ---- ① 工厂 + 策略选插件（精确寻址，INV-6）----
        ChannelPlugin plugin = resolvePlugin(channelCode);

        // ---- ②③ 验签 + 报文转换（插件钩子）----
        ParsedCallback parsed;
        try {
            parsed = plugin.parseCallback(envelope);
        } catch (BizException ex) {
            // 验签失败 / 报文不可解析：403，且不触达任何状态推进（INV-10）
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "signature");
            log.warn("channel callback rejected before convergence channel={} reason={}",
                    channelCode, ex.getMessage());
            return ChannelCallbackAck.rejected("rejected: " + ex.getMessage());
        }

        // ---- ④ 内核统一封装 + 跨域通知 ----
        ChannelPayNotified notified = toNotified(plugin.channelCode(), parsed);
        // callWith（而非 runWith）：需要拿回 Payment 的处理结论来构造应答。
        // 两者的 try/finally 语义一致，均不吞异常——Business 侧的异常已由端口转成结论。
        PayNotifyOutcome outcome = DyeContext.callWith(DyeMode.SANDBOX,
                () -> notifyPort.onChannelPayResult(notified));

        return switch (outcome.status()) {
            case ACCEPTED -> ChannelCallbackAck.ok(plugin.callbackAckBody());
            case REJECTED -> ChannelCallbackAck.ok("rejected: " + outcome.detail());
            case ERROR -> ChannelCallbackAck.ok("processing error");
        };
    }

    /**
     * 按路径上的渠道码精确解析插件；非插件渠道或未声明回调能力 ⇒ 直接失败（INV-6）。
     *
     * <p>刻意<b>不</b>捕获这里的 {@code BizException}：它表达「这个渠道根本不该收到回调」，
     * 是编程/配置错误（路由配置把回调指到了不支持回调的渠道），不是渠道报文的问题——
     * 伪装成 200/403 只会掩盖配置错误。</p>
     */
    private ChannelPlugin resolvePlugin(String channelCode) {
        PaymentChannel channel = registry.resolve(channelCode);
        if (!(channel instanceof ChannelPlugin plugin)) {
            throw BizException.of(ErrorCodes.NOT_FOUND,
                    "channel '" + channelCode + "' is not a channel plugin and cannot receive callbacks");
        }
        if (!plugin.acceptsCallback()) {
            throw BizException.of(ErrorCodes.NOT_FOUND,
                    "channel '" + channelCode + "' declares no callback path");
        }
        return plugin;
    }

    /**
     * 把插件翻译产物封装成跨域入向事件（第 ④ 步的「内核统一」）。
     *
     * <p>内核只搬运平台语义：三档结论、渠道交易号、渠道声称的金额币种、原因。
     * 渠道私有字段名一个都不出现——这是「内核不认识渠道」在回调路径上的落点。</p>
     *
     * <p><b>{@code channelNo} 传 {@code null}</b>：入向通知的寻址键是 {@code paymentNo}
     * （渠道只会给商户订单号），网关单号是平台侧标识；在回调这一刻反查它只是为填一个
     * 下游不消费的字段，却要多一次未必成功的读库。故入向契约里该字段可空，
     * 权威值仍以 {@code payment_attempts.channel_no} 为准。</p>
     */
    private static ChannelPayNotified toNotified(String channelCode, ParsedCallback parsed) {
        ParsedCallback.NotifiedAmount notifiedAmount = parsed.notifiedAmount();
        return new ChannelPayNotified(null, parsed.paymentNo(), channelCode,
                toStatus(parsed.result().status()),
                parsed.result().channelReference(),
                notifiedAmount == null ? null : notifiedAmount.amountMinor(),
                notifiedAmount == null ? null : notifiedAmount.currencyCode(),
                parsed.result().reason(),
                Instant.now());
    }

    /** 网关域三档结论 → 跨域口径（1:1，语义同名同义）。 */
    private static ChannelPayStatus toStatus(ChannelResult.Status status) {
        return switch (status) {
            case SUCCESS -> ChannelPayStatus.SUCCESS;
            case FAILURE -> ChannelPayStatus.FAILURE;
            case UNKNOWN -> ChannelPayStatus.UNKNOWN;
        };
    }
}
