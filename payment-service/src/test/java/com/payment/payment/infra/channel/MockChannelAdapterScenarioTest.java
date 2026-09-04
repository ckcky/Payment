package com.payment.payment.infra.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.core.rpc.TransportCode;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.ChannelResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Mock 渠道场景配置化（ADR-0049）。
 *
 * <p>场景原本硬编码为 {@code SUCCESS}，UNKNOWN / 失败 / 超时三条路径在生产进程里演不出来。
 * 本测试锁定两件事：① 每个合法场景名都能被解析并产出对应结果；
 * ② 非法值<b>立即失败</b>并给出合法取值清单——绝不允许「配错了却静默走默认成功」。</p>
 */
class MockChannelAdapterScenarioTest {

    private static final ChargeRequest REQUEST =
            new ChargeRequest("PM1000000000000001", 1L, 1000L, "CNY", "MOCK");

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SUCCESS, SUCCESS",
            "FAILURE, FAILURE",
            "BUSINESS_UNKNOWN, UNKNOWN",
            "TIMEOUT, UNKNOWN",
            "TRANSPORT_ERROR, UNKNOWN"
    })
    @DisplayName("合法场景名被解析，charge 返回对应结果状态")
    void legalScenarioIsParsed(String configured, ChannelResult.Status expected) {
        ChannelResult result = new MockChannelAdapter(configured, 1500L).charge(REQUEST);
        assertThat(result.status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("默认配置（SUCCESS）保持向后兼容：charge 返回成功并带渠道引用")
    void defaultScenarioStaysSuccess() {
        ChannelResult result = new MockChannelAdapter("SUCCESS", 1500L).charge(REQUEST);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.SUCCESS);
        assertThat(result.channelReference()).isNotBlank();
    }

    @Test
    @DisplayName("非法场景名立即失败，异常信息含合法取值清单（FAIL FAST，不静默回落 SUCCESS）")
    void illegalScenarioFailsFast() {
        assertThatThrownBy(() -> new MockChannelAdapter("business-unknown", 1500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid payment.channel.mock-scenario")
                .hasMessageContaining("SUCCESS")
                .hasMessageContaining("BUSINESS_UNKNOWN");
    }

    @Test
    @DisplayName("空场景名同样失败，不会静默取默认成功")
    void blankScenarioFailsFast() {
        assertThatThrownBy(() -> new MockChannelAdapter("  ", 1500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid payment.channel.mock-scenario");
    }

    @Test
    @DisplayName("TIMEOUT 场景产出通信超时码（可重试），与业务无结论 UNKNOWN 区分")
    void timeoutScenarioIsTransportTimeout() {
        ChannelResult result = new MockChannelAdapter("TIMEOUT", 1500L).charge(REQUEST);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(result.transportCode()).isEqualTo(TransportCode.TIMEOUT);
        assertThat(result.retryable()).isTrue();
    }

    @Test
    @DisplayName("BUSINESS_UNKNOWN 场景不可重试：不猜成败，交主动查询收敛")
    void businessUnknownIsNotRetryable() {
        ChannelResult result = new MockChannelAdapter("BUSINESS_UNKNOWN", 1500L).charge(REQUEST);
        assertThat(result.status()).isEqualTo(ChannelResult.Status.UNKNOWN);
        assertThat(result.retryable()).isFalse();
    }
}
