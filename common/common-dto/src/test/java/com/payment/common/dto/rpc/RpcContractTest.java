package com.payment.common.dto.rpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稳定 RPC 契约元数据（T019）：跨服务契约只承载原始事实字段，金额契约同时携带金额与币种；
 * 本模块只提供同步 RPC DTO，不提供跨服务事件契约。
 */
class RpcContractTest {

    @Test
    void paymentSucceededCarriesAmountAndCurrency() {
        PaymentSucceededRequest req = PaymentSucceededRequest.withoutItems(
                "pay-1", "order-1", "txn-1", "user-1", 1250L, "CNY");
        assertThat(req.amountMinor()).isEqualTo(1250L);
        assertThat(req.currencyCode()).isEqualTo("CNY");
        assertThat(req.items()).isNull(); // payment 不持有明细，order 层富化（spec 018）
    }

    @Test
    void paymentSucceededCarriesItemLinesForItemGranularFulfillment() {
        PaymentSucceededRequest req = new PaymentSucceededRequest(
                "pay-1", "order-1", "txn-1", "user-1", 1250L, "CNY",
                List.of(new PaymentSucceededRequest.ItemLine(
                        "OI123", "SKU-1", "商品A", 2, 625L, "CNY")));
        assertThat(req.items()).hasSize(1);
        assertThat(req.items().get(0).orderItemNo()).isEqualTo("OI123");
        assertThat(req.items().get(0).quantity()).isEqualTo(2);
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
