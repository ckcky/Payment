package com.payment.payment.contract;

import com.payment.channel.application.ChannelResult;
import com.payment.channel.application.ChargeRequest;
import com.payment.channel.application.PaymentChannel;
import com.payment.channel.infra.plugins.mock.MockChannelAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道抽象与 Mock 渠道契约测试（T024）。
 */
class PaymentChannelContractTest {

    private static final ChargeRequest REQUEST = new ChargeRequest("PM1000000000000001", 100, "CNY", "mock");

    @Test
    void successScenarioReturnsSuccessWithReference() {
        PaymentChannel channel = new MockChannelAdapter(MockChannelAdapter.Scenario.SUCCESS);
        ChannelResult result = channel.charge(REQUEST);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(result.channelReference()).isNotNull();
    }

    @Test
    void failureScenarioReturnsFailureWithReason() {
        PaymentChannel channel = new MockChannelAdapter(MockChannelAdapter.Scenario.FAILURE);
        ChannelResult result = channel.charge(REQUEST);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.FAILURE);
        assertThat(result.reason()).isNotBlank();
    }

    @Test
    void timeoutScenarioReturnsUnknownWithoutReference() {
        PaymentChannel channel = new MockChannelAdapter(MockChannelAdapter.Scenario.TIMEOUT);
        ChannelResult result = channel.charge(REQUEST);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(result.channelReference()).isNull();
    }
}
