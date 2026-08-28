package com.payment.payment.infra.channel;

import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Mock 渠道（T035 契约的默认实现）：按场景返回成功 / 失败 / 超时（未知），不触碰真实资金。
 *
 * <p>超时（{@link Scenario#TIMEOUT}）返回 {@link ChannelResult.Status#UNKNOWN}，用于演示
 * 未知状态收敛流程。</p>
 *
 * <p>{@link #queryStatus(QueryStatusRequest)} 默认返回「仍不明确（UNKNOWN）」；测试可经由
 * {@link #setQueryResult(ChannelResult)} 注入权威成功/失败结果，演示主动查询收敛（spec US2）。</p>
 */
@Component
public class MockChannelAdapter implements PaymentChannel {

    public enum Scenario {
        SUCCESS,
        FAILURE,
        TIMEOUT,
        /** 瞬时错误：返回可重试的 TRANSIENT 失败（spec US3 / ADR-0012）。 */
        TRANSIENT
    }

    private final Scenario scenario;
    private final AtomicLong refGen = new AtomicLong();
    private ChannelResult queryResult = ChannelResult.unknown("mock query inconclusive");

    public MockChannelAdapter() {
        this(Scenario.SUCCESS);
    }

    public MockChannelAdapter(Scenario scenario) {
        this.scenario = scenario;
    }

    /** 设定主动查询返回结果（默认 UNKNOWN）。 */
    public void setQueryResult(ChannelResult queryResult) {
        this.queryResult = queryResult;
    }

    @Override
    public ChannelResult charge(ChargeRequest request) {
        return switch (scenario) {
            case SUCCESS -> ChannelResult.success("mock-ref-" + refGen.incrementAndGet());
            case FAILURE -> ChannelResult.failure("mock-ref-" + refGen.incrementAndGet(), "mock declined");
            case TIMEOUT -> ChannelResult.unknown("mock timeout");
            case TRANSIENT -> ChannelResult.transientFailure("mock transient error");
        };
    }

    @Override
    public ChannelResult refund(RefundRequest request) {
        return switch (scenario) {
            case SUCCESS -> ChannelResult.success("mock-refund-ref-" + refGen.incrementAndGet());
            case FAILURE -> ChannelResult.failure("mock-refund-ref-" + refGen.incrementAndGet(), "mock refund declined");
            case TIMEOUT -> ChannelResult.unknown("mock refund timeout");
            case TRANSIENT -> ChannelResult.transientFailure("mock transient refund error");
        };
    }

    @Override
    public ChannelResult queryStatus(QueryStatusRequest request) {
        return queryResult;
    }
}
