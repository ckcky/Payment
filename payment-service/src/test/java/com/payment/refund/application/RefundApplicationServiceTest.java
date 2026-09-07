package com.payment.refund.application;

import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款申请编排测试（US2 / spec 019 T107）：成功/重复幂等/超退款拒绝/未知结果/币种不匹配/
 * PMRF 单号生成/幂等键=TXRF/三路收敛。
 */
class RefundApplicationServiceTest {

    private final RefundTestStack stack = new RefundTestStack();

    private CreateRefundCommand cmd() {
        return new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
    }

    @Test
    void successfulFullRefundFiresAttemptLedgerOnce() {
        Refund refund = stack.appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.payment.attemptRequests).hasSize(1);
        assertThat(stack.ledger.postingKeys).hasSize(1);
    }

    @Test
    void newRefundGeneratesPmrfNumber() {
        // spec 019 / T107：新建退款单一律 PMRF（支付层退款执行单），存量 RF 不再新增
        Refund refund = stack.appService().createRefund(cmd());
        assertThat(refund.getRefundNo()).startsWith("PMRF");
    }

    @Test
    void idempotencyKeyUsesTransactionRefundNoAndReplays() {
        // spec 019 / T107：幂等键 = transaction_refund_no（TXRF），同号重试回放同一执行单
        RefundApplicationService service = stack.appService();
        CreateRefundCommand first = new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L,
                "CNY", "customer", "ignored-legacy-key", List.of(), "TX-1", "TXRF-1");

        Refund created = service.createRefund(first);
        assertThat(created.getIdempotencyKey()).isEqualTo("TXRF-1");
        assertThat(created.getTransactionRefundNo()).isEqualTo("TXRF-1");
        assertThat(created.getTransactionNo()).isEqualTo("TX-1");

        // 同 TXRF 重试（即使 legacy 幂等键字段不同）→ 回放同一执行单
        CreateRefundCommand retry = new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L,
                "CNY", "customer", "another-legacy-key", List.of(), "TX-1", "TXRF-1");
        Refund replayed = service.createRefund(retry);
        assertThat(replayed.getId()).isEqualTo(created.getId());
        assertThat(stack.payment.attemptRequests).hasSize(1);
    }

    @Test
    void duplicateIdempotencyKeyReturnsSameRefundWithoutSecondAttempt() {
        RefundApplicationService service = stack.appService();
        Refund first = service.createRefund(cmd());
        Refund second = service.createRefund(cmd());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stack.payment.attemptRequests).hasSize(1);
    }

    @Test
    void overRefundIsRejectedWithoutAttempt() {
        stack.payment.amount = new PaymentAmountQueryResponse("PM-1", "order-1", "user-1", 1000L, "CNY", "SUCCEEDED");

        Refund refund = stack.appService().createRefund(
                new CreateRefundCommand("order-1", "PM-1", "user-1", 1200L, "CNY", "customer",
                        "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stack.payment.attemptRequests).isEmpty();
    }

    @Test
    void unknownAttemptEndsUnknownWithoutLedger() {
        stack.payment.attemptStatus = "UNKNOWN";

        Refund refund = stack.appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(stack.ledger.postingKeys).isEmpty();
    }

    @Test
    void currencyMismatchIsRejected() {
        Refund refund = stack.appService().createRefund(
                new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L, "USD", "customer",
                        "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stack.payment.attemptRequests).isEmpty();
    }

    @Test
    void cumulativeCountsRequestedAmountForBothTerminalAndInTransit() {
        // 第一笔 300 全额成功（终态，计申请额 300）。
        assertThat(stack.appService().createRefund(
                new CreateRefundCommand("order-1", "PM-1", "user-1", 300L, "CNY", "customer",
                        "idem-1", List.of())).getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);

        // 第二笔 400 在途（渠道回 UNKNOWN，待权威结果收敛）：同样按申请额保守占位。
        // 累计 = 300 + 400 = 700 <= 1000，应被批准（不落 REJECTED）。
        stack.payment.attemptStatus = "UNKNOWN";
        Refund second = stack.appService().createRefund(
                new CreateRefundCommand("order-1", "PM-1", "user-1", 400L, "CNY", "customer",
                        "idem-2", List.of()));
        assertThat(second.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(second.getStatus()).isNotEqualTo(RefundStatus.REJECTED);

        // 第三笔 400 会令累计达 1100 > 1000：H1 防超退 MUST 拒绝，且不发起渠道尝试。
        stack.payment.attemptStatus = "SUCCEEDED";
        int attemptsBefore = stack.payment.attemptRequests.size();
        Refund third = stack.appService().createRefund(
                new CreateRefundCommand("order-1", "PM-1", "user-1", 400L, "CNY", "customer",
                        "idem-3", List.of()));
        assertThat(third.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stack.payment.attemptRequests).hasSize(attemptsBefore);
    }
}
