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

    @Test
    void refundCommandCarriesTransactionRefundNo() {
        // spec 019 / ADR-0067：order 先生成 TXRF，payment 幂等键 = TXRF，响应 refundNo 即 PMRF
        RefundCommandRequest req = new RefundCommandRequest(
                "TXRF1", "TX1", "PM1", "OR1", "user-1", 500L, "CNY");
        assertThat(req.transactionRefundNo()).isEqualTo("TXRF1");
        assertThat(req.paymentNo()).isEqualTo("PM1");
        assertThat(req.amountMinor()).isEqualTo(500L);

        RefundCommandResponse resp = new RefundCommandResponse("PMRF1", "PROCESSING");
        assertThat(resp.refundNo()).isEqualTo("PMRF1");
        assertThat(resp.status()).isEqualTo("PROCESSING");
    }

    @Test
    void refundResultNotificationCarriesBothNos() {
        // payment → order 收口通知：TXRF + PMRF 双号互传，终态 + 失败原因
        RefundResultNotification n = new RefundResultNotification(
                "TXRF1", "PMRF1", "TX1", "OR1", "PM1", 500L, "CNY", "SUCCEEDED", null);
        assertThat(n.transactionRefundNo()).isEqualTo("TXRF1");
        assertThat(n.paymentRefundNo()).isEqualTo("PMRF1");
        assertThat(n.status()).isEqualTo("SUCCEEDED");
        assertThat(n.failureReason()).isNull();
    }
}
