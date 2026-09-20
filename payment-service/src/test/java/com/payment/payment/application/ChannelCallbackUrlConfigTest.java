package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.support.PaymentTestStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 渠道回调地址（notify / return）**配置单值**的装配测试（spec 030 / FR-103 + tasks Q5 裁决）。
 *
 * <h3>为什么需要这个类</h3>
 * spec 030 的 T31 要求编排层在构造 {@link ChargeRequest} 时填充 {@code callbackUrls}，
 * Q5 裁决「{@code notify_url} 配置单值（不按单动态拼）」。但实现落地时这两项都缺失：
 * 编排层恒传 {@code null}，且全仓检索不到任何 {@code notify_url} 配置项。后果是
 * {@code AlipayChannelAdapter#sandboxCharge} 的「缺 notifyUrl ⇒ 400」守卫
 * （FR-103 / INV-8）在 live 链路**必然**触发——沙箱下单 100% 失败。
 *
 * <p>本类把「配置 → {@code ChargeRequest.callbackUrls}」这段装配锁死，
 * 同时锁死「未配置 ⇒ {@code null}」的零回归基线（mock 链路不读该字段）。</p>
 *
 * <h3>注入方式说明</h3>
 * 生产代码用 {@code @Value} 字段注入（避免改动既有构造器签名、既有测试零改动）。
 * 既有测试是直接 {@code new} 应用服务的，不经 Spring 容器，故此处用
 * {@link ReflectionTestUtils} 模拟容器注入——这与生产注入路径等价。
 */
class ChannelCallbackUrlConfigTest {

    private static final String NOTIFY_URL =
            "https://demo.example.com/internal/channels/alipay/notify";
    private static final String RETURN_URL = "https://demo.example.com/return";

    /** 只记录 {@code charge} 入参、恒定成功的渠道桩。 */
    private static final class RecordingChannel implements PaymentChannel {

        private ChargeRequest lastCharge;

        @Override
        public String channelCode() {
            return "MOCK";
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            lastCharge = request;
            return ChannelResult.success("CH-CONFIG-1");
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            return ChannelResult.success("CH-CONFIG-1");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            return ChannelResult.success("CH-CONFIG-1");
        }
    }

    /**
     * 组装一个已注入回调地址配置的应用服务。
     *
     * @param notifyUrl 模拟 {@code payment.channel.notify-url}（{@code null}/空 = 未配置）
     * @param returnUrl 模拟 {@code payment.channel.return-url}
     */
    private static RecordingChannel chargeWith(String idempotencyKey,
                                               String notifyUrl, String returnUrl) {
        RecordingChannel channel = new RecordingChannel();
        PaymentTestStack stack = new PaymentTestStack();
        PaymentApplicationService service = stack.appService(channel);
        ReflectionTestUtils.setField(service, "channelNotifyUrl", notifyUrl);
        ReflectionTestUtils.setField(service, "channelReturnUrl", returnUrl);
        service.createPaymentIntentWithRouting(
                new CreatePaymentCommand("txn-1", "order-1", "user-1", 100, "CNY",
                        idempotencyKey, "MOCK"),
                false);
        return channel;
    }

    @Test
    @DisplayName("配置 notify-url/return-url ⇒ 渠道下单请求携带回调地址 [FR-103][Q5]")
    void configuredUrlsAreCarriedToChannel() {
        RecordingChannel channel = chargeWith("cb-cfg-1", NOTIFY_URL, RETURN_URL);

        assertThat(channel.lastCharge).as("渠道必须被调用").isNotNull();
        assertThat(channel.lastCharge.callbackUrls()).as("回调地址必须被填充").isNotNull();
        assertThat(channel.lastCharge.callbackUrls().notifyUrl()).isEqualTo(NOTIFY_URL);
        assertThat(channel.lastCharge.callbackUrls().returnUrl()).isEqualTo(RETURN_URL);
    }

    @Test
    @DisplayName("只配 notify-url ⇒ returnUrl 为空（returnUrl 非资金事实，允许缺失）[FR-103]")
    void notifyOnlyLeavesReturnUrlNull() {
        RecordingChannel channel = chargeWith("cb-cfg-2", NOTIFY_URL, "");

        assertThat(channel.lastCharge.callbackUrls()).isNotNull();
        assertThat(channel.lastCharge.callbackUrls().notifyUrl()).isEqualTo(NOTIFY_URL);
        assertThat(channel.lastCharge.callbackUrls().returnUrl()).isNull();
    }

    @Test
    @DisplayName("未配置 notify-url ⇒ callbackUrls 为 null（与 spec 030 前逐字节一致，零回归）")
    void unconfiguredLeavesCallbackUrlsNull() {
        RecordingChannel channel = chargeWith("cb-cfg-3", null, null);

        assertThat(channel.lastCharge).as("渠道仍必须被调用（行为不变）").isNotNull();
        assertThat(channel.lastCharge.callbackUrls())
                .as("未配置时不填充——mock 链路不读该字段，既有断言全部不变")
                .isNull();
    }

    @Test
    @DisplayName("空白 notify-url（' '）视为未配置 ⇒ callbackUrls 为 null")
    void blankNotifyUrlIsTreatedAsUnconfigured() {
        RecordingChannel channel = chargeWith("cb-cfg-4", "   ", RETURN_URL);

        assertThat(channel.lastCharge.callbackUrls()).isNull();
    }
}
