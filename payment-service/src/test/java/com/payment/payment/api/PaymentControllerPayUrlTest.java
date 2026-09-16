package com.payment.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.web.MockCashierProperties;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PaymentController#createPayment} 的 payUrl 通路（ADR-0048 修订版 + Feature 028）。
 *
 * <p>契约：mock-cashier.enabled=true → 以 defer=true 建单且响应附 payUrl；
 * enabled=false（默认）→ defer=false 且 payUrl 为 null，既有契约零变化。</p>
 *
 * <p><b>Feature 028（FR-027）</b>：payUrl 与响应的 {@code channelCode} 一律取
 * <b>路由后的最终渠道</b>（{@code RoutedPayment.channelCode()}），不再回显请求里的原始值——
 * 故本测试显式让请求带 <em>null</em> 渠道、由桩返回 {@code ALIPAY}，断言两者都是 ALIPAY。</p>
 */
class PaymentControllerPayUrlTest {

    private static Payment processingPayment(long id) {
        return Payment.rehydrate(id, "PM-test", "txn-1", "order-9", "user-1", 9900, "CNY",
                "idem-pay-url-1", PaymentStatus.PROCESSING, 1L, null, 0, null, 0, 1);
    }

    private static CreatePaymentRequest request() {
        return new CreatePaymentRequest("order-9", "txn-1", "user-1", 9900, "CNY",
                "idem-pay-url-1", "mock");
    }

    /** 只表达支付意图（不传渠道）的请求：路由后应得到 ALIPAY。 */
    private static CreatePaymentRequest requestWithoutChannel() {
        return new CreatePaymentRequest("order-9", "txn-1", "user-1", 9900, "CNY",
                "idem-pay-url-1", null);
    }

    @Test
    @DisplayName("enabled=true：defer 调用 + 响应附收银台 payUrl，且渠道码取路由结果")
    void payUrlPresentWhenMockCashierEnabled() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        // 请求未指定渠道（null），Router 决策为 ALIPAY —— 对外必须暴露 ALIPAY
        when(appService.createPaymentIntentWithRouting(any(), eq(true)))
                .thenReturn(new PaymentApplicationService.RoutedPayment(processingPayment(42L), "ALIPAY"));

        MockCashierProperties props = new MockCashierProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:8091");
        PaymentController controller = new PaymentController(appService, resolution, props);

        CreatePaymentResponse response = controller.createPayment(requestWithoutChannel());

        assertThat(response.payUrl()).isEqualTo(
                "http://localhost:8091/cashier?paymentNo=PM-test&orderNo=order-9&amountMinor=9900&currencyCode=CNY&channelCode=ALIPAY");
        assertThat(response.channelCode()).isEqualTo("ALIPAY");
        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(appService).createPaymentIntentWithRouting(any(), eq(true));
    }

    @Test
    @DisplayName("enabled=false（默认）：非 defer 调用，payUrl 为 null")
    void payUrlNullWhenMockCashierDisabled() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        when(appService.createPaymentIntentWithRouting(any(), eq(false)))
                .thenReturn(new PaymentApplicationService.RoutedPayment(processingPayment(43L), "mock"));

        PaymentController controller = new PaymentController(appService, resolution,
                new MockCashierProperties());

        CreatePaymentResponse response = controller.createPayment(request());

        assertThat(response.payUrl()).isNull();
        assertThat(response.paymentNo()).isEqualTo("PM-test");
        verify(appService).createPaymentIntentWithRouting(any(), eq(false));
    }

    @Test
    @DisplayName("回归 FR-027：显式渠道时响应渠道码 == 请求渠道（零干预）")
    void explicitChannelIsEchoedAsRoutedCode() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        when(appService.createPaymentIntentWithRouting(any(), eq(false)))
                .thenReturn(new PaymentApplicationService.RoutedPayment(processingPayment(44L), "WECHAT"));

        PaymentController controller = new PaymentController(appService, resolution,
                new MockCashierProperties());

        CreatePaymentResponse response = controller.createPayment(
                new CreatePaymentRequest("order-9", "txn-1", "user-1", 9900, "CNY", "k", "WECHAT"));

        assertThat(response.channelCode()).isEqualTo("WECHAT");
    }
}
