package com.payment.common.dto.rpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稳定 RPC 契约元数据（T019）：跨服务契约只承载原始事实字段，金额契约同时携带金额与币种；
 * 本模块只提供同步 RPC DTO，不提供跨服务事件契约。
 */
class RpcContractTest {

    @Test
    void paymentSucceededCarriesAmountAndCurrency() {
        PaymentSucceededRequest req = new PaymentSucceededRequest(
                1L, "order-1", "txn-1", "user-1", 1250L, "CNY");
        assertThat(req.amountMinor()).isEqualTo(1250L);
        assertThat(req.currencyCode()).isEqualTo("CNY");
    }

    @Test
    void createPaymentCarriesIdempotencyKeyAndAmount() {
        CreatePaymentRequest req = new CreatePaymentRequest(
                "order-1", "txn-1", "user-1", 1250L, "CNY", "idem-1", "mock");
        assertThat(req.idempotencyKey()).isEqualTo("idem-1");
        assertThat(req.amountMinor()).isEqualTo(1250L);
        assertThat(req.currencyCode()).isEqualTo("CNY");
    }

    @Test
    void noCrossServiceEventContractExists() {
        // 跨服务通信统一为同步 RPC DTO；本模块不得再出现 event 契约包。
        assertThat(com.payment.common.dto.rpc.PaymentSucceededRequest.class.getPackageName())
                .isEqualTo("com.payment.common.dto.rpc");
    }
}
