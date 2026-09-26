package com.payment.channel.infra.plugins.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.channel.application.ChannelResult;
import com.payment.channel.application.spi.ChannelCallbackEnvelope;
import com.payment.channel.application.spi.ChannelPluginDescriptor;
import com.payment.channel.application.spi.ParsedCallback;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.channel.PaymentScene;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Mock 渠道（T035 契约的默认实现 / 兼容渠道，FR-011）：
 * 身份为 {@code MOCK}，用于「未指定渠道的旧脚本」与既有约 30 个测试文件。
 *
 * <p><b>本类是 {@link AbstractMockChannelAdapter} 的薄子类</b>——自 Feature 028 起，全部行为
 * （金额尾数确定性故障注入、退款「受理 + 异步推送」、每实例独立 {@code runId}、
 * {@code mock-scenario} 严格枚举解析）已上移到基类；spec 037 / T6 又把基类整体接到
 * {@code AbstractChannelPlugin} 上，故本类<b>同时是渠道插件</b>（FR-014），
 * 需要自己提供的只剩：① 身份 {@code MOCK}；② 插件自描述；③ <b>全部 6 个既有构造签名</b>
 * （FR-011/FR-036/SC-012）。</p>
 *
 * <p>保留构造签名的目的很实际：10 个 {@code new MockChannelAdapter(...)} 的测试文件与未指定渠道的
 * 旧脚本<b>零改动</b>——结构重构不该把成本转嫁给调用方。</p>
 *
 * <p>渠道 {@link AlipayChannelAdapter} / {@link DouyinChannelAdapter}
 * 与本类行为完全一致（FR-013），差别只在身份、自描述与各自可配的 scenario。
 * （{@code WECHAT} 自 spec 039 起改按插件范式接入，见
 * {@code com.payment.channel.infra.plugins.wechat.WechatChannelPlugin}。）</p>
 */
@Component
public class MockChannelAdapter extends AbstractMockChannelAdapter {

    public static final String CODE = "MOCK";

    /** 纯 mock 渠道的全部场景都是本地模拟，故声明全支持（与迁移前接口默认值同一口径）。 */
    private static final Set<PaymentScene> SUPPORTED_SCENES = Set.of(PaymentScene.values());

    /**
     * 插件自描述：<b>回调路径 {@code MOCK}</b>（spec 041 / T12）。
     *
     * <h3>为什么从「无回调」改为「有回调」</h3>
     * <p>此前 mock 族的 HTTP 回调走的是 <b>payment 域</b>的两个内部端点
     * （{@code /internal/payments/{paymentNo}/channel-callback} 与
     * {@code /internal/payments/refunds/{refundNo}/channel-callback}），
     * 由演示/联调工具（{@code mock-channel-web} 的两个 proxy）调用。那是
     * 「渠道域的回调由 payment 域端点接收」的边界倒置（FR-005 要求回调仅由 Channel API 接收），
     * 也是「同一件事两条路」的歧义来源。</p>
     *
     * <p>T12 把这两个端点删除，演示回调改打<b>唯一入口</b>
     * {@code POST /callbacks/channels/MOCK}：mock 族因此必须具备回调能力，
     * 解析平台原生的 JSON 结果报文（见 {@link #parseCallback}）。</p>
     *
     * <p><b>注意与「进程内推送」的分工</b>：mock 族的<b>退款异步推送</b>
     * （{@code AbstractChannelPlugin.scheduleRefundPush}）仍然是<b>进程内</b>直接调
     * {@code PaymentResultPort}，不经过 HTTP——那条路径与真实渠道的回调语义等价，
     * 无需绕道网络。本回调路径服务的是<b>外部驱动的演示/联调</b>（proxy 模拟渠道推结果）。</p>
     */
    private static final ChannelPluginDescriptor DESCRIPTOR =
            ChannelPluginDescriptor.withCallback(CODE, "Mock 渠道（本地模拟）", SUPPORTED_SCENES, false, CODE);

    /** 平台原生回调报文解析器（mock 族专用格式；渠道私有协议不在此列）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ChannelPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String channelCode() {
        return CODE;
    }

    /**
     * 解析平台原生回调报文（spec 041 / T12）。
     *
     * <h3>报文格式（{@code application/json}）</h3>
     * <pre>
     *   支付：{"paymentNo":"PM…","status":"SUCCESS|FAILURE|UNKNOWN",
     *          "channelReference":"ch-1","reason":null,"amountMinor":100}
     *   退款：{"refundNo":"PMRF…","status":"SUCCESS|FAILURE|UNKNOWN",
     *          "channelReference":"ch-1","reason":null}
     * </pre>
     * <p>判别式是<b>寻址键字段</b>：带 {@code refundNo} ⇒ 退款；否则要求 {@code paymentNo}
     * ⇒ 支付。这与 {@code PaymentResultPort} 的两条入向操作一一对应（FR-006）。</p>
     *
     * <h3>验签：本期空实现（ADR-0025 负责人决议，2026-08-30）</h3>
     * <p>ADR-0025 决议「渠道回调签名校验改为预留函数、空实现就行」，故本方法
     * <b>不校验</b> {@code X-Channel-Signature} / {@code X-Channel-Timestamp}。
     * 真实渠道的验签各自落在自己的插件里（支付宝表单验签 / 微信 V3 验签 / Stripe 原始字节验签），
     * 本类只服务 mock 族的演示链路。接入真实验签时，改造点在本方法内，
     * 且必须同时把 {@code mock-channel-web} 的 FORGED/NONE 演示模式反转为拒绝断言。</p>
     *
     * <p>解析失败（空体 / 非法 JSON / 缺寻址键 / 状态值非法）一律抛
     * {@code BizException(INVALID_ARGUMENT)} ⇒ 端点转 403，<b>不触达</b>任何状态推进（INV-10）。</p>
     */
    @Override
    public ParsedCallback parseCallback(ChannelCallbackEnvelope envelope) {
        String body = envelope == null ? null : envelope.rawBody();
        if (body == null || body.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "mock callback body must not be empty");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "unparseable mock callback body");
        }
        String status = text(root, "status");
        String channelReference = text(root, "channelReference");
        String reason = text(root, "reason");
        ChannelResult result = toResult(status, channelReference, reason);

        String refundNo = text(root, "refundNo");
        if (refundNo != null) {
            return ParsedCallback.refund(refundNo, result);
        }
        String paymentNo = text(root, "paymentNo");
        if (paymentNo == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "mock callback must carry paymentNo (pay) or refundNo (refund)");
        }
        Long amountMinor = root.hasNonNull("amountMinor") ? root.get("amountMinor").asLong() : null;
        ParsedCallback.NotifiedAmount amount = amountMinor == null
                ? ParsedCallback.NotifiedAmount.UNKNOWN
                : ParsedCallback.NotifiedAmount.of(amountMinor, text(root, "currencyCode"));
        return ParsedCallback.pay(paymentNo, result, amount);
    }

    /** 三档结论映射：状态值非法即拒绝（不猜结论）。 */
    private static ChannelResult toResult(String status, String channelReference, String reason) {
        if (status == null) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "mock callback must carry status");
        }
        return switch (status) {
            case "SUCCESS" -> ChannelResult.success(channelReference);
            case "FAILURE" -> ChannelResult.businessFailure(channelReference, reason);
            case "UNKNOWN" -> ChannelResult.businessUnknown(reason);
            default -> throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "invalid mock callback status: " + status + "; expected SUCCESS|FAILURE|UNKNOWN");
        };
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 兼容构造①：默认 SUCCESS 场景。 */
    public MockChannelAdapter() {
        this(Scenario.SUCCESS);
    }

    /** 兼容构造②：显式场景。 */
    public MockChannelAdapter(Scenario scenario) {
        this(scenario, 1500L);
    }

    /** 兼容构造③：场景 + HTTP 超时预算。 */
    public MockChannelAdapter(Scenario scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs) {
        this(scenario, httpTimeoutMs, false, 1000L);
    }

    /** 兼容构造④：场景名 + 超时（测试/演示脚本按场景名构建，同步模式）。 */
    public MockChannelAdapter(String scenario, long httpTimeoutMs) {
        super(scenario, httpTimeoutMs);
    }

    /** 兼容构造⑤：全参（测试 / 演示脚本显式指定形态）。 */
    public MockChannelAdapter(Scenario scenario, long httpTimeoutMs,
                              boolean refundAsync, long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /**
     * Spring 主构造⑥（ADR-0049 + spec 019 / D7）：场景由 {@code payment.channel.mock-scenario} 决定
     * （默认 {@code SUCCESS}）；退款异步模式由 {@code payment.channel.refund-async} 决定（默认开）。
     *
     * <p><b>取值必须严格等于 {@link Scenario} 枚举名</b>（大写下划线）。不做别名、不做大小写容错：
     * 非法值直接让 Bean 创建失败并给出合法取值清单（ADR-0049 第 2 条）。</p>
     */
    @Autowired
    public MockChannelAdapter(@Value("${payment.channel.mock-scenario:SUCCESS}") String scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
                              @Value("${payment.channel.refund-async:true}") boolean refundAsync,
                              @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        super(scenario, httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }
}
