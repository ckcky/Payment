package com.payment.payment.api;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.payment.application.PaymentApplicationService;
import com.payment.payment.application.PaymentUnknownResolutionService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentController#pay} 的契约（spec 041 / FR-020）。
 *
 * <p>本测试取代改造前的 {@code PaymentControllerPayUrlTest}。改造前它断言「Controller 读
 * {@code mockCashier.enabled} → 拼收银台 URL」，即把<b>属于渠道域的行为</b>当作 Controller 契约来测；
 * 改造后 Controller 只做透传，故本测试只断言<b>透传是否忠实</b>：
 * payUrl / 渠道码 / 状态一律取自 {@code PayResult}，Controller 不加工、不兜底、不回显请求原始值。</p>
 */
class PaymentControllerPayTest {

    private static Payment processingPayment(long id) {
        return Payment.rehydrate(id, "PM-test", "txn-1", "order-9", "user-1", 9900, "CNY",
                "idem-pay-url-1", PaymentStatus.PROCESSING, 1L, null, 0, null, 0, 1, "M001");
    }

    private static CreatePaymentRequest request(String channelCode) {
        return new CreatePaymentRequest("order-9", "txn-1", "user-1", 9900, "CNY",
                "idem-pay-url-1", channelCode, "M001");
    }

    @Test
    @DisplayName("payUrl / 渠道码 / 状态一律取自 PayResult（Controller 不加工）")
    void responseMirrorsPayResult() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        // 请求未指定渠道（null），路由后为 ALIPAY —— 对外必须暴露 ALIPAY，不得回显 null
        when(appService.pay(any(CreatePaymentRequest.class))).thenReturn(new PaymentApplicationService.PayResult(
                processingPayment(42L), "ALIPAY",
                "http://localhost:8091/cashier?paymentNo=PM-test&channelCode=ALIPAY"));

        PaymentController controller = new PaymentController(appService, resolution);

        CreatePaymentResponse response = controller.pay(request(null));

        assertThat(response.payUrl())
                .isEqualTo("http://localhost:8091/cashier?paymentNo=PM-test&channelCode=ALIPAY");
        assertThat(response.channelCode()).isEqualTo("ALIPAY");
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.paymentNo()).isEqualTo("PM-test");
        verify(appService).pay(any(CreatePaymentRequest.class));
    }

    @Test
    @DisplayName("无凭证（同步扣款主链）⇒ payUrl 为 null，不被 Controller 兜底成任何值")
    void nullPayUrlIsPassedThrough() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        when(appService.pay(any(CreatePaymentRequest.class)))
                .thenReturn(new PaymentApplicationService.PayResult(processingPayment(43L), "MOCK", null));

        PaymentController controller = new PaymentController(appService, resolution);

        assertThat(controller.pay(request("mock")).payUrl()).isNull();
    }

    @Test
    @DisplayName("查询：paymentNo 与 transactionId 一并交给应用服务，Controller 不做寻址判断")
    void queryDelegatesBothCriteria() {
        PaymentApplicationService appService = mock(PaymentApplicationService.class);
        PaymentUnknownResolutionService resolution = mock(PaymentUnknownResolutionService.class);
        when(appService.queryPayment("PM-test", null)).thenReturn(processingPayment(44L));

        PaymentController controller = new PaymentController(appService, resolution);

        assertThat(controller.query("PM-test", null).paymentNo()).isEqualTo("PM-test");
        verify(appService).queryPayment("PM-test", null);
    }
}
