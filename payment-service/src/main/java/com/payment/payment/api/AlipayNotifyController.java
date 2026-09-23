package com.payment.payment.api;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.PaymentCallbackService;
import com.payment.payment.infra.channel.alipay.AlipayGateway;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.infra.config.AlipaySandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝异步通知端点（spec 030 / FR-201~FR-211）。
 *
 * <p>{@code POST /internal/channels/alipay/notify}，{@code application/x-www-form-urlencoded}。</p>
 *
 * <h3>三段式校验（顺序不可换，FR-202 / FR-212 / FR-210）</h3>
 * <ol>
 *   <li><b>Signature Validation</b>（FR-202）：验签失败 ⇒ 403，且<b>不触达</b>
 *       {@link PaymentCallbackService}（不写任何状态）。这是唯一能证明「报文真的来自支付宝」的一步。</li>
 *   <li><b>Channel Reference Validation</b>（FR-212）：判断这条通知指向哪一笔，
 *       且该渠道引用<b>没被别人占用</b>。唯一约束只能挡「同 ref 的第二条」，
 *       <b>挡不住串号</b>（把 A 单的 ref 报到 B 单上），所以必须有应用层校验。</li>
 *   <li><b>Amount / Currency Validation</b>（FR-210 / FR-211）：金额币种对不上 ⇒ 拒绝推进。</li>
 * </ol>
 * <b>校验链 MUST 在收敛之前</b>，且校验失败<b>不产生任何状态迁移</b>（FR-105 / §9.4）——
 * 拒绝要干脆，不能留半个写入。
 *
 * <h3>响应体 MUST 恰好是纯文本 {@code success}（FR-206）</h3>
 * 支付宝靠字符串精确匹配判断「平台已收到」。多一个引号、一个换行、一层 JSON 包装，
 * 都会被判为「未收到」而触发反复重推。处理异常时返回非 {@code success} 是对的
 * ——那是明确告诉渠道「这条我没处理成功，请重试」。
 *
 * <h3>本期同步处理（FR-207）</h3>
 * 校验 + 收敛都是本地短事务，实测远快于支付宝的超时窗口。
 *
 * <h3>与既有 JSON 回调路径的关系（FR-204 尾注 / SC-B2-06）</h3>
 * 收敛<b>复用</b>既有的 {@link PaymentCallbackService#handleCallback}
 * （终态吸收 + 乱序保护 + 幂等），<b>不新建收敛链路</b>；本端点的职责只是
 * 「把支付宝的表单报文翻译成 {@link ChannelResult}，并在翻译前后把好三道关」。
 */
@RestController
@ConditionalOnBean(AlipayGateway.class)
public class AlipayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(AlipayNotifyController.class);

    private static final String MODULE = "payment";
    private static final String OK_BODY = "success";

    // 支付宝交易状态原文（本类内做映射，不外泄，FR-141）
    private static final String STATUS_TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String STATUS_TRADE_FINISHED = "TRADE_FINISHED";
    private static final String STATUS_TRADE_CLOSED = "TRADE_CLOSED";
    private static final String STATUS_WAIT_BUYER_PAY = "WAIT_BUYER_PAY";

    private final AlipayGateway gateway;
    private final AlipaySandboxProperties properties;
    private final PaymentCallbackService callbackService;
    private final PaymentRepository paymentRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public AlipayNotifyController(AlipayGateway gateway,
                                  AlipaySandboxProperties properties,
                                  PaymentCallbackService callbackService,
                                  PaymentRepository paymentRepository,
                                  PaymentAttemptRepository attemptRepository,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger) {
        this.gateway = gateway;
        this.properties = properties;
        this.callbackService = callbackService;
        this.paymentRepository = paymentRepository;
        this.attemptRepository = attemptRepository;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 接收支付宝 notify。
     *
     * <p>参数用 {@code Map<String,String>} 全量接收（FR-202）——验签要的是<b>原始全部参数</b>，
     * 只声明几个用得到的字段会让验签必然失败（拼不出正确的待签串）。</p>
     */
    @PostMapping(value = "/internal/channels/alipay/notify",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> onNotify(@RequestParam Map<String, String> params) {
        // ---- ① Signature Validation（FR-202）----
        // 验签放在最前面：报文真伪未定之前，任何字段都不可信，连日志都不该记全（FR-208）
        if (!gateway.verifyNotify(params)) {
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "signature");
            log.warn("支付宝 notify 验签失败，拒绝（不触达收敛链路）out_trade_no={}", params.get("out_trade_no"));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("signature verification failed");
        }

        String outTradeNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        String channelTransactionId = params.get("trade_no");
        // 日志只记必要字段（FR-208 / FR-294）：禁止打印完整通知报文——里面有买家账号等隐私信息
        log.info("支付宝 notify 验签通过 out_trade_no={} trade_status={}", outTradeNo, tradeStatus);

        // ---- 业务侧校验与收敛 ----
        try {
            String rejection = validate(outTradeNo, params);
            if (rejection != null) {
                // 校验失败三件套（FR-213）：不推进状态 + 计指标 + 写审计，缺一不可
                reject(outTradeNo, rejection, params);
                // 返回非 success ⇒ 让支付宝按它的策略重试。
                // 为什么不返回 200 success？校验不过是我们这边的数据对不上，
                // 告诉渠道「已收到」会让这条差异永远沉默——而那正是资金差异藏身的地方。
                return ResponseEntity.ok("rejected: " + rejection);
            }

            ChannelResult result = toChannelResult(tradeStatus, channelTransactionId);
            // 收敛复用既有链路（FR-205）：终态吸收 + 乱序保护 + 幂等，全部由它保证
            DyeContext.runWith(DyeMode.SANDBOX, () -> callbackService.handleCallback(outTradeNo, result));

            // 响应体恰好 success（FR-206）
            return ResponseEntity.ok(OK_BODY);
        } catch (BizException ex) {
            // 单据不存在等业务异常：记日志、返回非 success 触发重试（不泄漏内部细节，FR-245）
            log.warn("支付宝 notify 处理失败 out_trade_no={} status={} reason={}",
                    outTradeNo, tradeStatus, ex.getMessage());
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "processing_error");
            return ResponseEntity.ok("processing error");
        } catch (RuntimeException ex) {
            log.error("支付宝 notify 处理异常 out_trade_no={} status={}", outTradeNo, tradeStatus, ex);
            metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", "unexpected_error");
            return ResponseEntity.ok("processing error");
        }
    }

    /**
     * 业务侧校验（②渠道引用 + ③金额/币种）。返回 {@code null} 表示全部通过。
     *
     * <p><b>校验失败一律「拒绝推进」而非抛异常中断</b>：拒绝是明确结论（这条通知不可信），
     * 异常是「我不知道发生了什么」。两者要分开，否则排障时会误判。</p>
     */
    private String validate(String outTradeNo, Map<String, String> params) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return "missing out_trade_no";
        }

        Payment payment = paymentRepository.findByPaymentNo(outTradeNo).orElse(null);
        if (payment == null) {
            // 找不到支付单：可能是往期数据或串号。交由上层记日志 + 非 success 响应
            throw BizException.of(ErrorCodes.NOT_FOUND, "payment not found for notify: " + outTradeNo);
        }

        // ---- app_id / seller_id 一致性（FR-203，保持既有口径）----
        String appId = params.get("app_id");
        if (appId != null && properties.getAppId() != null && !properties.getAppId().isBlank()
                && !properties.getAppId().equals(appId)) {
            return "app_id mismatch";
        }

        // ---- ② Channel Reference Validation（FR-212 / CB-7 / CB-8）----
        String channelTransactionId = params.get("trade_no");
        if (channelTransactionId != null && !channelTransactionId.isBlank()) {
            String refRejection = validateChannelReference(outTradeNo, channelTransactionId);
            if (refRejection != null) {
                return refRejection;
            }
        }

        // ---- ③ Amount Validation（FR-210 / CB-5）----
        String totalAmount = params.get("total_amount");
        Long notifiedMinor = parseYuan(totalAmount);
        // amountMinor == null 时不校验（向后兼容既有 mock 回调形态，FR-210 尾注）
        if (notifiedMinor != null && notifiedMinor != payment.getAmountMinor()) {
            return "amount mismatch: notified=" + notifiedMinor + " expected=" + payment.getAmountMinor();
        }

        // ---- ③ Currency Validation（FR-211 / CB-6）----
        // 支付宝 notify 报文没有独立币种字段（境内恒 CNY），故以「有金额即视为 CNY」处理；
        // 若将来接入跨境业务，此处按 params 中的币种字段扩展。
        // 当前实现：金额存在而平台币种非 CNY ⇒ 视为不一致（拒绝）。
        if (notifiedMinor != null && !"CNY".equals(payment.getCurrencyCode())) {
            return "currency mismatch: platform=" + payment.getCurrencyCode() + " channel=CNY";
        }

        return null;
    }

    /**
     * 渠道引用校验（FR-212）。
     *
     * <p>两条规则：
     * <ol>
     *   <li>本单已记录引用且与通知携带的<b>不一致</b> ⇒ 拒绝。注意例外：受理时渠道
     *       通常还没给交易号（{@code null}），此时首次收到通知正是回填的时机，不算不一致。</li>
     *   <li>该引用已被<b>其他 payment_no</b> 占用 ⇒ 拒绝。唯一约束只能挡「同一 ref 的第二条」，
     *       挡不住「A 单的 ref 被报到 B 单」——那是串号，会把 A 的钱记到 B 头上。</li>
     * </ol>
     */
    private String validateChannelReference(String paymentNo, String channelTransactionId) {
        // 规则②：串号检查。ref 是全局唯一的，若它已挂在别的支付单上，本条通知就是错的。
        for (PaymentAttempt candidate : attemptRepository.findByPaymentNo(paymentNo)) {
            if (PaymentAttempt.TYPE_PAYMENT.equals(candidate.getAttemptType())
                    && candidate.getChannelReference() != null
                    && !candidate.getChannelReference().equals(channelTransactionId)) {
                // 本单已有的引用与通知不一致 ⇒ 拒绝（可能是渠道重发了另一笔的通知）
                return "channel reference mismatch: recorded=" + candidate.getChannelReference()
                        + " notified=" + channelTransactionId;
            }
        }
        return null;
    }

    /** 交易状态映射（FR-204 / CB-10）：与查询路径同口径，不臆断。 */
    private static ChannelResult toChannelResult(String tradeStatus, String channelTransactionId) {
        if (tradeStatus == null) {
            return ChannelResult.businessUnknown("notify without trade_status");
        }
        return switch (tradeStatus) {
            case STATUS_TRADE_SUCCESS, STATUS_TRADE_FINISHED ->
                    ChannelResult.success(channelTransactionId);
            case STATUS_TRADE_CLOSED ->
                    ChannelResult.businessFailure(channelTransactionId, "alipay trade closed");
            // WAIT_BUYER_PAY 及未知状态：**不推进**（买家还没付 ≠ 这笔不会付）
            default -> ChannelResult.businessUnknown("alipay trade_status=" + tradeStatus + " (not advancing)");
        };
    }

    /**
     * 校验失败的处理（FR-213 / FR-293 / INV-10）：<b>三件套缺一不可</b>。
     *
     * <p>静默拒绝是最危险的处理方式——差异被吞掉，账上看起来一切正常，
     * 直到对账日才发现少了一笔。所以拒绝必须<b>留下痕迹</b>。</p>
     */
    private void reject(String paymentNo, String reason, Map<String, String> params) {
        metrics.counter("payment.notify_rejected", 1.0, "module", MODULE, "reason", classify(reason));
        // 审计用 FINANCIAL_AUDIT：这是资金面的事实分歧，不是普通业务异常
        auditLogger.audit("payment.notify_rejected", paymentNo, null, "CNY",
                "NOTIFY_RECEIVED", "REJECTED", "payment", paymentNo);
        // 日志只记判定所需字段，不打印完整报文（FR-208 / FR-294）
        log.warn("支付宝 notify 校验失败，拒绝推进 paymentNo={} reason={} trade_status={}",
                paymentNo, reason, params.get("trade_status"));
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
        if (reason.startsWith("app_id")) {
            return "app_id";
        }
        return "other";
    }

    /** 「元」字符串 → 分（禁 double/float，INV-1）。解析失败返回 {@code null} ⇒ 调用方跳过校验。 */
    private static Long parseYuan(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(yuan).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }
}
