package com.payment.payment.infra.channel;

import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mock 渠道（T035 契约的默认实现）：按场景返回成功 / 业务失败 / 通信失败，不触碰真实资金。
 *
 * <p>所有结果都通过<b>双响应码</b>表达（ADR-0012）：{@code TransportCode} 描述通信是否成功，
 * {@code BusinessCode} 描述通信成功后的业务结论。重试判定只看通信码，本类不参与。</p>
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
    /**
     * 每次 JVM 启动生成的运行级唯一前缀：渠道引用在 {@code payment_attempts.channel_reference}
     * 上有唯一约束兜底（重复回调映射同一渠道交互），但 Mock 引用由本类客户端生成。
     * 若不带运行级前缀，重启后 {@code mock-ref-1} 会与历史库中的残留行撞唯一约束导致建单失败，
     * 故以 UUID 前缀保证跨重启全局唯一（生产由真实 PSP 交易号保证，无需此兜底）。
     */
    private final String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final AtomicLong refGen = new AtomicLong();
    private ChannelResult queryResult = ChannelResult.businessUnknown("mock query inconclusive");
    /**
     * 可配置的「渠道实际退款金额」（最小货币单位）。为 {@code null} 时表示全额退款（= 申请金额）；
     * 设为小于申请金额的正值即可模拟「部分退款」场景（ADR-0016）。仅 SUCCESS 场景生效。
     */
    private Long configuredRefundMinor;

    public MockChannelAdapter() {
        this(Scenario.SUCCESS);
    }

    public MockChannelAdapter(Scenario scenario) {
        this(scenario, 1500L);
    }

    public MockChannelAdapter(Scenario scenario,
                              @Value("${payment.channel.http-timeout-ms:1500}") long httpTimeoutMs) {
        this.scenario = scenario;
        this.httpTimeoutMs = httpTimeoutMs;
    }

    /** 对外（渠道 / 外部系统）HTTP 调用的超时预算，全服务统一 1.5s（ADR-0012 超时口径）。 */
    public long getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    /** 设定主动查询返回结果（默认业务无结论）。 */
    public void setQueryResult(ChannelResult queryResult) {
        this.queryResult = queryResult;
    }

    /** 设定「渠道实际退款金额」（部分退款模拟）。{@code null} 表示全额退款。 */
    public void setRefundMinor(Long refundMinor) {
        this.configuredRefundMinor = refundMinor;
    }

    @Override
    public ChannelResult charge(ChargeRequest request) {
        return switch (scenario) {
            case SUCCESS -> ChannelResult.success("mock-ref-" + runId + "-" + refGen.incrementAndGet());
            case FAILURE -> ChannelResult.businessFailure("mock-ref-" + runId + "-" + refGen.incrementAndGet(),
                    "mock declined");
            case TIMEOUT -> ChannelResult.timeout(
                    "mock timeout: no response within " + httpTimeoutMs + "ms");
            case TRANSPORT_ERROR -> ChannelResult.transportFailure(
                    TransportCode.CONNECTION_ERROR, "mock connection reset");
            case BUSINESS_UNKNOWN -> ChannelResult.businessUnknown("mock channel still processing");
        };
    }

    @Override
    public ChannelResult refund(RefundRequest request) {
        return switch (scenario) {
            case SUCCESS -> {
                long refunded = configuredRefundMinor != null ? configuredRefundMinor : request.amountMinor();
                yield ChannelResult.success("mock-refund-ref-" + runId + "-" + refGen.incrementAndGet(), refunded);
            }
            case FAILURE -> ChannelResult.businessFailure("mock-refund-ref-" + runId + "-" + refGen.incrementAndGet(),
                    "mock refund declined");
            case TIMEOUT -> ChannelResult.timeout(
                    "mock refund timeout: no response within " + httpTimeoutMs + "ms");
            case TRANSPORT_ERROR -> ChannelResult.transportFailure(
                    TransportCode.CONNECTION_ERROR, "mock refund connection reset");
            case BUSINESS_UNKNOWN -> ChannelResult.businessUnknown("mock refund still processing");
        };
    }

    @Override
    public ChannelResult queryStatus(QueryStatusRequest request) {
        return queryResult;
    }
}
