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
 * {@link PaymentController#createPayment} 的 payUrl 通路（ADR-0048 修订版）。
 *
 * <p>契约：mock-cashier.enabled=true → createPaymentIntent 以 defer=true 调用且响应附 payUrl；
 * enabled=false（默认）→ defer=false 且 payUrl 为 null，既有契约零变化。</p>
 */
class PaymentControllerPayUrlTest {

    private static Payment processingPayment(long id) {
        return Payment.rehydrate(id, "PM-test", "txn-1", "order-9", "user-1", 9900, "CNY",
                "idem-pay-url-1", PaymentStatus.PROCESSING, 1L, null, 0, null, 0);
    }

    private static CreatePaymentRequest request() {
        return new CreatePaymentRequest("order-9", "txn-1", "user-1", 9900, "CNY",
                "idem-pay-url-1", "mock");
    }

    @Test
    @DisplayName("enabled=true：defer 调用 + 响应附收银台 payUrl")
    void payUrlPresentWhenMockCashierEnabled() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        when(appService.createPaymentIntent(any(), eq(true)))
                .thenReturn(processingPayment(42L));

        MockCashierProperties props = new MockCashierProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://localhost:8091");
        PaymentController controller = new PaymentController(appService, resolution, props);

        CreatePaymentResponse response = controller.createPayment(request());

        assertThat(response.payUrl()).isEqualTo(
                "http://localhost:8091/cashier?paymentId=42&orderId=order-9&amountMinor=9900&currencyCode=CNY");
        assertThat(response.status()).isEqualTo("PROCESSING");
        verify(appService).createPaymentIntent(any(), eq(true));
    }

    @Test
    @DisplayName("enabled=false（默认）：非 defer 调用，payUrl 为 null")
    void payUrlNullWhenMockCashierDisabled() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        when(appService.createPaymentIntent(any(), eq(false)))
                .thenReturn(processingPayment(43L));

        PaymentController controller = new PaymentController(appService, resolution,
                new MockCashierProperties());

        CreatePaymentResponse response = controller.createPayment(request());

        assertThat(response.payUrl()).isNull();
        assertThat(response.paymentId()).isEqualTo(43L);
        verify(appService).createPaymentIntent(any(), eq(false));
    }
}
