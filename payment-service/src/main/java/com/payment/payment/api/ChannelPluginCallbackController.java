package com.payment.payment.api;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.PaymentCallbackService;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.spi.ChannelCallbackEnvelope;
import com.payment.payment.application.channel.spi.ChannelPlugin;
import com.payment.payment.application.channel.spi.ParsedCallback;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 渠道异步回调的<b>通用插件端点</b>（渠道插件化内核 / SPI-09）：
 * {@code POST /internal/channels/{channelCode}/callback}。
 *
 * <h3>它消灭了什么</h3>
 * 改造前每接一家渠道就要新增一个专属 Controller（{@code AlipayNotifyController} 是第一个）
 * ——于是「回调三段式校验」这条资金防线在每家渠道各有一份实现，漏一次就是真实的资金差异。
 * 本端点把三段式校验<b>收敛到内核一处</b>：
 *
 * <ol>
 *   <li><b>Signature Validation</b>：由<b>插件</b>完成（验签算法与基串是渠道私有知识）。
 *       失败 ⇒ 403，且<b>不触达</b>状态推进（INV-10）。</li>
 *   <li><b>Channel Reference Validation</b>：内核通用。渠道引用与已记录的不一致 ⇒ 拒绝
 *       （串号会把 A 的钱记到 B 头上）。</li>
 *   <li><b>Amount / Currency Validation</b>：内核通用。金额币种对不上 ⇒ 拒绝（FR-210 / FR-211）。</li>
 * </ol>
 *
 * <h3>为什么路径里带 {@code channelCode}（INV-6）</h3>
 * 回调必须<b>精确寻址</b>到「当初那笔交互走的渠道」，绝不重新路由。
 * 路径变量天然满足这一点：谁的通知进谁的门。
 * 资金安全红线：退款/通知换渠道 = 钱处理到错的地方。</p>
 *
 * <h3>为什么内核不解析报文</h3>
 * 本端点把请求<b>原样搬运</b>成 {@link ChannelCallbackEnvelope}（原始字节 + 表单参数 + 头），
 * 不做任何渠道语义解析——不读 {@code out_trade_no}、不读 {@code data.object.id}。
 * 内核一旦认识某个字段名，第二家渠道就得改内核。</p>
 *
 * <h3>与既有端点的关系（迁移中状态）</h3>
 * <ul>
 *   <li>{@link ChannelCallbackController}（{@code /internal/payments/{paymentNo}/channel-callback}）：
 *       <b>平台内部</b>的 mock 回调入口，非渠道协议，保持不动；</li>
 *   <li>{@code AlipayNotifyController}：支付宝专属端点，既有测试与联调脚本依赖它，
 *       迁移到本端点后再删除。两条路径并存是<b>刻意登记的技术债</b>，见《Channel 域架构评估》。</li>
 * </ul>
 */
@RestController
public class ChannelPluginCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ChannelPluginCallbackController.class);

    private static final String MODULE = "payment";
    private static final String FORM_CONTENT_TYPE = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    private final ChannelRegistry registry;
    private final PaymentCallbackService callbackService;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public ChannelPluginCallbackController(ChannelRegistry registry,
                                           PaymentCallbackService callbackService,
                                           PaymentRepository paymentRepository,
                                           PaymentAttemptRepository attemptRepository,
                                           BusinessMetrics metrics,
                                           StructuredAuditLogger auditLogger) {
        this.registry = registry;
        this.callbackService = callbackService;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 接收渠道异步回调。
     *
     * <p><b>body 只被读取一次</b>：先读原始字节（Stripe 验签必须用原始字节，
     * 重新序列化 JSON 会改变签名基串），再按 content-type 决定是否需要解析成表单。
     * 若改用 {@code @RequestParam}，容器会先消费 body，JSON 形态的渠道将拿不到原始报文。</p>
     */
    @PostMapping("/internal/channels/{channelCode}/callback")
    public ResponseEntity<String> onCallback(@PathVariable String channelCode,
                                             HttpServletRequest request) throws IOException {
        // ---- 渠道寻址（INV-6：精确寻址，禁止路由）----
        ChannelPlugin plugin = resolvePlugin(channelCode);

        byte[] raw = StreamUtils.copyToByteArray(request.getInputStream());
        String body = new String(raw, StandardCharsets.UTF_8);
        String contentType = request.getContentType();
        Map<String, String> form = contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(FORM_CONTENT_TYPE)
                ? parseForm(body)
                : Map.of();

        ChannelCallbackEnvelope envelope =
                new ChannelCallbackEnvelope(lowerCaseHeaders(request), form, body);

        // ---- ① Signature Validation + 翻译（插件内完成）----
        ParsedCallback parsed;
        try {
            parsed = plugin.parseCallback(envelope);
        } catch (BizException ex) {
            // 验签失败 / 报文不可解析：403，且不触达任何状态推进（INV-10）
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "signature");
            log.warn("channel callback rejected before convergence channel={} reason={}",
                    channelCode, ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("rejected: " + ex.getMessage());
        }

        String paymentNo = parsed.paymentNo();
        ChannelResult result = parsed.result();

        try {
            // ---- ②③ 通用资金校验 ----
            String rejection = validate(paymentNo, result, parsed.notifiedAmount());
            if (rejection != null) {
                reject(paymentNo, rejection);
                return ResponseEntity.ok("rejected: " + rejection);
            }

            // 收敛复用既有链路（终态吸收 + 乱序保护 + 幂等，全部由它保证）
            DyeContext.runWith(DyeMode.SANDBOX, () -> callbackService.handleCallback(paymentNo, result));
            return ResponseEntity.ok(plugin.callbackAckBody());
        } catch (BizException ex) {
            log.warn("channel callback processing failed channel={} paymentNo={} reason={}",
                    channelCode, paymentNo, ex.getMessage());
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "processing_error");
            return ResponseEntity.ok("processing error");
        } catch (RuntimeException ex) {
            log.error("channel callback unexpected error channel={} paymentNo={}", channelCode, paymentNo, ex);
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "unexpected_error");
            return ResponseEntity.ok("processing error");
        }
    }

    /** 按路径上的渠道码精确解析插件；非插件渠道或未声明回调能力 ⇒ 404（INV-6）。 */
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
     * 业务侧校验（②渠道引用 + ③金额/币种）。返回 {@code null} 表示全部通过。
     *
     * <p>与 {@code AlipayNotifyController#validate} 同口径，只是去掉了渠道私有的
     * {@code app_id} 检查（那属于①签名/身份段，由插件负责）。</p>
     */
    private String validate(String paymentNo, ChannelResult result, ParsedCallback.NotifiedAmount notified) {
        Payment payment = paymentRepository.findByPaymentNo(paymentNo).orElse(null);
        if (payment == null) {
            throw BizException.of(ErrorCodes.NOT_FOUND, "payment not found for callback: " + paymentNo);
        }

        // ---- ② Channel Reference Validation ----
        String channelReference = result.channelReference();
        if (channelReference != null && !channelReference.isBlank()) {
            String refRejection = validateChannelReference(paymentNo, channelReference);
            if (refRejection != null) {
                return refRejection;
            }
        }

        // ---- ③ Amount / Currency Validation ----
        if (notified.isKnown()) {
            if (notified.amountMinor() != payment.getAmountMinor()) {
                return "amount mismatch: notified=" + notified.amountMinor()
                        + " expected=" + payment.getAmountMinor();
            }
            if (!notified.currencyCode().equalsIgnoreCase(payment.getCurrencyCode())) {
                return "currency mismatch: notified=" + notified.currencyCode()
                        + " expected=" + payment.getCurrencyCode();
            }
        }
        return null;
    }

    /**
     * 渠道引用校验：本单已记录的引用与通知不一致 ⇒ 拒绝（串号检查）。
     *
     * <p>例外：受理阶段渠道可能还没给流水号（{@code null}），首次通知正是回填时机，
     * 不算不一致。</p>
     */
    private String validateChannelReference(String paymentNo, String channelReference) {
        for (PaymentAttempt candidate : attemptRepository.findByPaymentNo(paymentNo)) {
            if (PaymentAttempt.TYPE_PAYMENT.equals(candidate.getAttemptType())
                    && candidate.getChannelReference() != null
                    && !candidate.getChannelReference().equals(channelReference)) {
                return "channel reference mismatch: recorded=" + candidate.getChannelReference()
                        + " notified=" + channelReference;
            }
        }
        return null;
    }

    /** 校验失败的三件套（FR-213）：不推进状态 + 计指标 + 写审计，缺一不可。 */
    private void reject(String paymentNo, String reason) {
        metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", classify(reason));
        auditLogger.audit("payment.notify_rejected", paymentNo, null, null,
                "NOTIFY_RECEIVED", "REJECTED", "payment", paymentNo);
        log.warn("channel callback rejected paymentNo={} reason={}", paymentNo, reason);
    }

    private static String classify(String reason) {
        if (reason.startsWith("amount")) {
            return "amount";
        }
        if (reason.startsWith("currency")) {
            return "currency";
        }
        if (reason.startsWith("channel reference")) {
            return "channel_reference";
        }
        return "other";
    }

    /** 头名统一小写——渠道签名头大小写不保证（{@code Stripe-Signature} / {@code stripe-signature}）。 */
    private static Map<String, String> lowerCaseHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }

    /** {@code a=1&b=2} → Map（值按 UTF-8 解码）。 */
    private static Map<String, String> parseForm(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return params;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
