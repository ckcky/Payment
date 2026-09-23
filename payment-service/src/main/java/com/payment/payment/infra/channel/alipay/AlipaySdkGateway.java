package com.payment.payment.infra.channel.alipay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.payment.infra.channel.alipay.AlipayGateway;
import com.payment.payment.infra.config.AlipaySandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝网关的 SDK 实现（spec 030 / FR-132 / FR-141 / INV-7）。
 *
 * <p><b>本类是全仓唯一允许 import {@code com.alipay.api} 的类</b>（Maven 坐标写作
 * {@code com.alipay.sdk:alipay-sdk-java}，但 Java 包名是 {@code com.alipay.api}；
 * 两者混淆会让包名匹配型的架构门禁静默空转）；INV-7 / SC-A-09 由 ArchUnit 构建期断言。
 * 所有渠道协议细节——签名、验签、参数组装、金额换算、错误归一化
 * ——全部关在这里；出去只有 {@link AlipayGateway} 的平台自有类型（FR-141）。
 * 换实现（如纯 JDK 的 {@code HttpClient} + {@code SHA256withRSA}）只需另写一个类，零扩散。</p>
 *
 * <h3>为什么不薄</h3>
 * 这一层看着「重」，但它承载的是<b>协议翻译</b>：把渠道的错误码、状态字符串、金额格式
 * 翻成平台语义。这些翻译规则如果散到 Adapter 或应用层，就会出现「同一个错误码在两个地方
 * 映射成两种结果」——那种不一致在资金路径上就是事故。</p>
 *
 * <h3>装配门控</h3>
 * {@code @ConditionalOnProperty} 保证 {@code enabled=false}（默认）时本 Bean 不存在，
 * 沙箱代码路径自然不可达（FR-133）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "payment.channel.adapters.alipay.sandbox",
        name = "enabled", havingValue = "true")
public class AlipaySdkGateway implements AlipayGateway {

    private static final Logger log = LoggerFactory.getLogger(AlipaySdkGateway.class);

    /** 支付宝成功码。 */
    private static final String ALIPAY_SUCCESS_CODE = "10000";

    // ---- 渠道交易状态原文（只在本类出现，不外泄） ----
    private static final String STATUS_TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String STATUS_TRADE_FINISHED = "TRADE_FINISHED";
    private static final String STATUS_TRADE_CLOSED = "TRADE_CLOSED";
    private static final String STATUS_WAIT_BUYER_PAY = "WAIT_BUYER_PAY";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AlipaySandboxProperties properties;
    private final AlipayClient client;

    public AlipaySdkGateway(AlipaySandboxProperties properties) throws AlipayApiException {
        this.properties = properties;
        // 用 SDK 的 9 参构造（serverUrl, appId, privateKey, format, charset, alipayPublicKey,
        // signType, **proxyHost**, **proxyPort**）——最后两位显式传 null：
        // 本机 shell 默认挂着 http_proxy，若不显式传空，SDK 可能读到系统代理把沙箱请求
        // 绕进代理（演示环境排障时这种「明明配对了却连不上」极难查）。
        // 超时（FR-140）走 Properties 里的 httpTimeoutMs，由 AlipayChannelAdapter 侧保证
        // MUST < payment.reliability.timeout(30s)。
        this.client = new DefaultAlipayClient(properties.getGatewayUrl(), properties.getAppId(),
                properties.getAppPrivateKey(), properties.getFormat(), properties.getCharset(),
                properties.getAlipayPublicKey(), properties.getSignType(), null, null);
        log.info("支付宝沙箱网关已装配 {}", properties);
    }

    @Override
    public PagePayResult pagePay(String outTradeNo, long amountMinor, String currency,
                                 String subject, String notifyUrl, String returnUrl, Instant expireAt) {
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(outTradeNo);
        model.setTotalAmount(toYuanPlainString(amountMinor)); // FR-139：禁 double/float
        model.setSubject(subject);
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        if (expireAt != null) {
            model.setTimeExpire(expireAt.toString());
        }

        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(notifyUrl);
        request.setReturnUrl(returnUrl);
        request.setBizModel(model);

        try {
            // pageExecute 返回**已签名的「自动提交表单」HTML**（放在 response.body）——
            // 不是 URL！浏览器渲染这段 HTML 会自动 POST 到网关换回收银台页。
            // 把它当 URL 直接 window.open 只会得到空白页（spec 030 联调实测过）。
            // 沙箱下这一步不会让买家付钱，只是拿到「去哪儿付款」的凭证（FR-136）。
            AlipayTradePagePayResponse response = client.pageExecute(request);
            String pageFormHtml = response == null ? null : response.getBody();
            if (pageFormHtml == null || pageFormHtml.isBlank()) {
                // 没拿到凭证就不是「受理成功」——不能返回 accepted，否则买家去了一个空白页
                log.warn("alipay.trade.page.pay 未返回付款凭证（表单 HTML） outTradeNo={} code={}", outTradeNo,
                        response == null ? "<null>" : response.getCode());
                return PagePayResult.transportFailure("page pay returned no credential payload (channel did not accept)");
            }
            log.info("alipay.trade.page.pay 已受理 outTradeNo={} 金额分={}", outTradeNo, amountMinor);
            return PagePayResult.ok(pageFormHtml);
        } catch (AlipayApiException ex) {
            // 通信层异常（超时 / 断连 / 证书问题）⇒ 可重试语义
            log.warn("alipay.trade.page.pay 通信失败 outTradeNo={}: {}", outTradeNo, ex.getMessage());
            return PagePayResult.transportFailure("alipay page pay transport failure: " + ex.getMessage());
        }
    }

    @Override
    public QueryResult query(String outTradeNo, String channelTransactionId) {
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        if (channelTransactionId != null && !channelTransactionId.isBlank()) {
            model.setTradeNo(channelTransactionId); // 渠道交易号优先（定位更准）
        } else {
            model.setOutTradeNo(outTradeNo);
        }

        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizModel(model);

        AlipayTradeQueryResponse response;
        try {
            response = client.execute(request);
        } catch (AlipayApiException ex) {
            log.warn("alipay.trade.query 通信失败 outTradeNo={}: {}", outTradeNo, ex.getMessage());
            return QueryResult.transportFailure("alipay query transport failure: " + ex.getMessage());
        }

        if (response == null) {
            return QueryResult.transportFailure("alipay query returned null response");
        }

        String tradeStatus = response.getTradeStatus();

        // ⚠️ ACQ.TRADE_NOT_EXIST 等业务错误码要单独判：「交易不存在」是**业务结论**
        // 且语义上是「这笔没成立」。但此处刻意**不**映射为 CLOSED——渠道说「查不到」时，
        // 平台侧的去向应由上层（查询收敛）按既有规则处理，本层不越权定成败。
        if (!response.isSuccess() && !isTradeNotExist(response)) {
            return QueryResult.unknown("alipay query returned non-success: "
                    + safeReason(response));
        }

        AlipayTradeStatus status = mapTradeStatus(tradeStatus);
        Long totalMinor = parseYuanToMinor(response.getTotalAmount());

        return new QueryResult(status, response.getTradeNo(), totalMinor,
                parseCurrency(totalMinor), true,
                "alipay tradeStatus=" + tradeStatus);
    }

    @Override
    public RefundResult refund(String outTradeNo, String channelTransactionId,
                               String refundNo, long amountMinor, String currency, String reason) {
        AlipayTradeRefundModel model = new AlipayTradeRefundModel();
        model.setOutTradeNo(outTradeNo);
        if (channelTransactionId != null && !channelTransactionId.isBlank()) {
            model.setTradeNo(channelTransactionId);
        }
        model.setOutRequestNo(refundNo); // 幂等键：同一退款单号重复请求渠道只退一次
        model.setRefundAmount(toYuanPlainString(amountMinor)); // FR-139
        if (reason != null && !reason.isBlank()) {
            model.setRefundReason(reason);
        }

        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        request.setBizModel(model);

        AlipayTradeRefundResponse response;
        try {
            response = client.execute(request);
        } catch (AlipayApiException ex) {
            log.warn("alipay.trade.refund 通信失败 outTradeNo={} refundNo={}: {}",
                    outTradeNo, refundNo, ex.getMessage());
            return RefundResult.transportFailure("alipay refund transport failure: " + ex.getMessage());
        }

        if (response == null) {
            return RefundResult.transportFailure("alipay refund returned null response");
        }
        // 沙箱下 refund 是**同步返回**：code=10000 即渠道已确认退款（FR-138）
        if (ALIPAY_SUCCESS_CODE.equals(response.getCode())) {
            log.info("alipay.trade.refund 成功 outTradeNo={} refundNo={} channelRefundNo={}",
                    outTradeNo, refundNo, response.getTradeNo());
            return RefundResult.ok(response.getTradeNo());
        }
        log.warn("alipay.trade.refund 被拒 outTradeNo={} refundNo={} code={} subCode={}",
                outTradeNo, refundNo, response.getCode(), response.getSubCode());
        return RefundResult.rejected(safeReason(response));
    }

    @Override
    public boolean verifyNotify(Map<String, String> rawParams) {
        if (rawParams == null || rawParams.isEmpty()) {
            return false;
        }
        try {
            // ⚠️ **必须用 rsaCheckV1，不能用 rsaCheckV2**（2026-09-20 沙箱真实回调实测踩坑）。
            //
            // 本 SDK 版本（alipay-sdk-java 4.40.996.ALL，已反编译核对）里两个方法的语义**是反的**：
            //     getSignCheckContentV1 → 剔除 sign **和** sign_type   ✅ 符合支付宝签名的规范
            //     getSignCheckContentV2 → **只**剔除 sign（保留 sign_type） ❌
            // 而支付宝给异步通知签名时**不含 sign_type**，于是 V2 拼出的待签串会多出一段
            // `sign_type=RSA2`，真实回调必然验签失败——现象是**所有 notify 都被 403 拒掉、
            // 支付单永远收敛不了**（本地 403 探针会掩盖这一点：空报文本来就该 403，
            // 「探针 403」证明不了密钥与算法口径正确）。
            //
            // 真报文对照（同一条 TRADE_SUCCESS 通知、同一把支付宝公钥）：
            //     V1 待签串 597 字符 → 验签通过
            //     V2 待签串 612 字符 → 验签失败
            // 回归测试见 AlipayNotifySignatureVerificationTest（含「sign_type 必须被剔除」的阳性对照）。
            //
            // ⚠️ 别再按方法名推断语义（"V2 比 V1 新所以更对"）——这是实测结论。
            // 传副本：SDK 的 getSignCheckContent* 会就地 remove，不污染调用方的 params。
            return AlipaySignature.rsaCheckV1(new LinkedHashMap<>(rawParams),
                    properties.getAlipayPublicKey(),
                    properties.getCharset(), properties.getSignType());
        } catch (AlipayApiException ex) {
            // 验签异常一律当「验签不通过」——绝不能因异常放行（INV-10）
            log.warn("支付宝 notify 验签异常（按不通过处理）: {}", ex.getMessage());
            return false;
        }
    }

    // ---- 协议翻译（全部关在本类，FR-141） ----

    /**
     * 金额换算：分 → 元字符串（FR-139 / INV-1）。
     *
     * <p><b>MUST 用 {@code BigDecimal.valueOf(amountMinor, 2).toPlainString()}</b>：
     * {@code BigDecimal.valueOf(long, int)} 走 {@code Long.toString} 构造，不经二进制浮点，
     * 无精度损失；{@code toPlainString()} 避免科学计数法（{@code 1E+2} 会被渠道拒）。
     * <b>禁 {@code double} / {@code float}</b>——{@code 0.1 + 0.2 != 0.3} 那类误差
     * 在资金上是实打实的差错。</p>
     */
    static String toYuanPlainString(long amountMinor) {
        return BigDecimal.valueOf(amountMinor, 2).toPlainString();
    }

    /**
     * 渠道返回的「元」字符串 → 分。
     *
     * <p>反方向同样禁止 {@code double}：{@code new BigDecimal(str)} 从字符串解析，
     * 不经过浮点。解析失败返回 {@code null}（调用方据此跳过金额校验，而非误判为不匹配）。</p>
     */
    static Long parseYuanToMinor(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(yuan).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            log.warn("无法把渠道金额解析为分: '{}'", yuan);
            return null;
        }
    }

    /**
     * 渠道交易状态 → 平台归一化取值（FR-137）。
     *
     * <p>{@code WAIT_BUYER_PAY} 与无法识别的值都映射为 <b>无结论</b>——渠道说「还没付」不等于
     * 「这笔一定不会付」，说「不认识」更不等于失败。<b>不臆断</b>（Constitution §V.7）。</p>
     */
    static AlipayTradeStatus mapTradeStatus(String tradeStatus) {
        if (tradeStatus == null) {
            return AlipayTradeStatus.UNKNOWN;
        }
        return switch (tradeStatus) {
            case STATUS_TRADE_SUCCESS, STATUS_TRADE_FINISHED -> AlipayTradeStatus.SUCCESS;
            case STATUS_TRADE_CLOSED -> AlipayTradeStatus.CLOSED;
            case STATUS_WAIT_BUYER_PAY -> AlipayTradeStatus.WAIT_BUYER_PAY;
            default -> AlipayTradeStatus.UNKNOWN;
        };
    }

    /** 币种：支付宝境内业务恒为 CNY；此处显式给出而非猜，便于将来扩展。 */
    private static String parseCurrency(Long totalMinor) {
        return totalMinor == null ? null : "CNY";
    }

    /** 「交易不存在」业务码：渠道明确说这笔查不到（与通信失败区分开）。 */
    private static boolean isTradeNotExist(AlipayTradeQueryResponse response) {
        String subCode = response.getSubCode();
        return subCode != null && subCode.startsWith("ACQ.TRADE_NOT_EXIST");
    }

    /**
     * 构造对外的错误说明：<b>只给平台语义，不带渠道私有错误码</b>（FR-141）。
     *
     * <p>渠道码写进日志（排障要），不写进返回值（会顺着返回值一路渗到应用层与 API 响应）。</p>
     */
    private static String safeReason(com.alipay.api.AlipayResponse response) {
        return "channel rejected the request (see payment-service logs for channel error code)";
    }
}