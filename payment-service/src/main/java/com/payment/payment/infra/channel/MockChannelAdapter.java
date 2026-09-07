package com.payment.payment.infra.channel;

import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.application.channel.RefundResultListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mock 渠道（T035 契约的默认实现）：按场景返回成功 / 业务失败 / 通信失败，不触碰真实资金。
 *
 * <p>所有结果都通过<b>双响应码</b>表达（ADR-0012）：{@code TransportCode} 描述通信是否成功，
 * {@code BusinessCode} 描述通信成功后的业务结论。重试判定只看通信码，本类不参与。</p>
 *
 * <p><b>退款异步模式（spec 019 / D7，默认开启）</b>：{@code refund()} 改「受理 + 异步推送」——
 * 请求当场返回 {@link ChannelResult#accepted}（受理流水号、无业务结论），延迟
 * {@code payment.channel.refund-async-delay-ms} 后经 {@link RefundResultListener} 推送权威结果
 * （等价真实渠道的 HTTP 回调）。同步模式保留可配（{@code payment.channel.refund-async=false}）。
 * 通信失败类场景（TIMEOUT/TRANSPORT_ERROR）不受异步模式影响——请求根本没被渠道受理，当场回传。</p>
 *
 * <p>场景由 {@code payment.channel.mock-scenario} 配置（默认 {@code SUCCESS}，ADR-0049）；
 * 主动查询结果由 {@link #setQueryResult} 设定，默认「渠道无结论」。</p>
 */
@Component
public class MockChannelAdapter implements PaymentChannel {

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
                Thread t = new Thread(r, "mock-refund-pusher");
                t.setDaemon(true);
                return t;
            });
    /** 退款结果推送目标（Spring 装配；纯单元测试可不注入——注入后才有异步推送）。 */
    private volatile RefundResultListener refundResultListener;
    /**
     * 每次 JVM 启动生成的运行级唯一前缀：渠道引用在 {@code payment_attempts.channel_reference}
     * 上有唯一约束兜底（重复回调映射同一渠道交互），但 Mock 引用由本类客户端生成。
     * 若不带运行级前缀，重启后 {@code mock-ref-1} 会与历史库中的残留行撞唯一约束导致建单失败，
     * 故以 UUID 前缀保证跨重启全局唯一（生产由真实 PSP 交易号保证，无需此兜底）。
     */
    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final AtomicLong refGen = new AtomicLong();
    private ChannelResult queryResult = ChannelResult.businessUnknown("mock query inconclusive");

    public MockChannelAdapter() {
        this(Scenario.SUCCESS);
    }

    public MockChannelAdapter(Scenario scenario) {
        this(scenario, 1500L);
    }

    public MockChannelAdapter(Scenario scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs) {
        this(scenario, httpTimeoutMs, false, 1000L);
    }

    /** 便捷构造（测试/演示脚本按场景名 + 超时构建，同步模式）：异步模式用 Spring 主构造配置。 */
    public MockChannelAdapter(String scenario, long httpTimeoutMs) {
        this(parseScenario(scenario), httpTimeoutMs, false, 1000L);
    }

    /**
     * Spring 主构造（ADR-0049 + spec 019 / D7）：场景由 {@code payment.channel.mock-scenario} 决定
     * （默认 {@code SUCCESS}）；退款异步模式由 {@code payment.channel.refund-async} 决定（默认开）。
     *
     * <p><b>取值必须严格等于 {@link Scenario} 枚举名</b>（大写下划线）。不做别名、不做大小写容错：
     * 非法值直接让 Bean 创建失败并给出合法取值清单，避免「配错了却静默走默认成功」——
     * 那是最难排查的假绿（ADR-0049 第 2 条）。</p>
     */
    @Autowired
    public MockChannelAdapter(@Value("${payment.channel.mock-scenario:SUCCESS}") String scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs,
                              @Value("${payment.channel.refund-async:true}") boolean refundAsync,
                              @Value("${payment.channel.refund-async-delay-ms:1000}") long refundAsyncDelayMs) {
        this(parseScenario(scenario), httpTimeoutMs, refundAsync, refundAsyncDelayMs);
    }

    /** 全参构造（测试 / 演示脚本显式指定形态）。 */
    public MockChannelAdapter(Scenario scenario, long httpTimeoutMs,
                              boolean refundAsync, long refundAsyncDelayMs) {
        this.scenario = scenario;
        this.httpTimeoutMs = httpTimeoutMs;
        this.refundAsync = refundAsync;
        this.refundAsyncDelayMs = refundAsyncDelayMs;
    }

    private static Scenario parseScenario(String raw) {
        String name = raw == null ? "" : raw.trim();
        try {
            return Scenario.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "invalid payment.channel.mock-scenario: '" + raw + "'; expected one of "
                            + java.util.Arrays.toString(Scenario.values()), e);
        }
    }

    /** 对外（渠道 / 外部系统）HTTP 调用的超时预算，全服务统一 1.5s（ADR-0012 超时口径）。 */
    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    /** 设定主动查询返回结果（默认业务无结论）。 */
    public void setQueryResult(ChannelResult queryResult) {
        this.queryResult = queryResult;
    }

    /** 注入退款结果推送目标（Spring 装配；未注入时异步模式退化为纯受理、不推送）。 */
    @Autowired(required = false)
    public void setRefundResultListener(RefundResultListener refundResultListener) {
        this.refundResultListener = refundResultListener;
    }

    /** 渠道引用前缀：按 channelCode 派生（未知渠道回落 mock-）。 */
    private static String channelPrefix(String channelCode) {
        if (channelCode == null || channelCode.isBlank()) {
            return "mock";
        }
        return switch (channelCode.toUpperCase()) {
            case "ALIPAY" -> "alipay";
            case "WECHAT" -> "wechat";
            case "DOUYIN" -> "douyin";
            default -> channelCode.toLowerCase();
        };
    }

    @Override
    public ChannelResult charge(ChargeRequest request) {
        // Feature 015 / P5：渠道引用带渠道前缀，便于对账/演示按渠道区分（alipay-/wechat-/douyin-/mock-）
        String ref = channelPrefix(request.channelCode()) + "-ref-" + runId + "-" + refGen.incrementAndGet();
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

    @Override
    public ChannelResult refund(RefundRequest request) {
        // 通信失败类场景：请求未被渠道受理，不受异步模式影响，当场回传。
        if (scenario == Scenario.TIMEOUT || scenario == Scenario.TRANSPORT_ERROR) {
            return switch (scenario) {
                case TIMEOUT -> ChannelResult.timeout(
                        "mock refund timeout: no response within " + httpTimeoutMs + "ms");
                default -> ChannelResult.transportFailure(
                        TransportCode.CONNECTION_ERROR, "mock refund connection reset");
            };
        }

        String ref = "mock-refund-ref-" + runId + "-" + refGen.incrementAndGet();

        if (refundAsync) {
            // 受理 + 异步推送（D7）：当场回「已受理无结论」，延迟后推送权威结果。
            scheduleRefundPush(request.refundNo(), ref);
            return ChannelResult.accepted(ref, "mock refund accepted, awaiting async callback");
        }

        return switch (scenario) {
            case SUCCESS -> ChannelResult.success(ref);
            case FAILURE -> ChannelResult.businessFailure(ref, "mock refund declined");
            case BUSINESS_UNKNOWN -> ChannelResult.businessUnknown("mock refund still processing");
            default -> ChannelResult.businessUnknown("mock refund still processing");
        };
    }

    /** 受理后延迟推送权威退款结果（SUCCESS 场景推成功，FAILURE 场景推业务拒绝；无监听器则跳过）。 */
    private void scheduleRefundPush(String refundNo, String ref) {
        if (refundResultListener == null) {
            return;
        }
        refundPusher.schedule(() -> {
            RefundResultListener listener = refundResultListener;
            if (listener == null) {
                return;
            }
            ChannelResult finalResult = scenario == Scenario.FAILURE
                    ? ChannelResult.businessFailure(ref, "mock refund declined")
                    : ChannelResult.success(ref);
            listener.onChannelRefundResult(refundNo, finalResult);
        }, refundAsyncDelayMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public ChannelResult queryStatus(QueryStatusRequest request) {
        return queryResult;
    }
}
