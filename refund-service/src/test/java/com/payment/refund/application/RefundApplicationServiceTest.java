package com.payment.refund.application;

import com.payment.common.dto.rpc.PaymentAmountQueryResponse;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款申请编排测试（US2）：成功/重复幂等/超退款拒绝/未知结果/币种不匹配。
 */
class RefundApplicationServiceTest {

    private final RefundTestStack stack = new RefundTestStack();

    private CreateRefundCommand cmd() {
        return new CreateRefundCommand("order-1", 1L, "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
    }

    @Test
    void successfulFullRefundFiresAttemptAndPostProcessOnce() {
        Refund refund = stack.appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.payment.attemptRequests).hasSize(1);
        assertThat(stack.entitlement.postProcessRequests).hasSize(1);
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
        stack.payment.amount = new PaymentAmountQueryResponse(1L, "order-1", "user-1", 1000L, "CNY", "SUCCEEDED");

        Refund refund = stack.appService().createRefund(
                new CreateRefundCommand("order-1", 1L, "user-1", 1200L, "CNY", "customer",
                        "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stack.payment.attemptRequests).isEmpty();
    }

    @Test
    void unknownAttemptEndsUnknownWithoutPostProcess() {
        stack.payment.attemptStatus = "UNKNOWN";

        Refund refund = stack.appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(stack.entitlement.postProcessRequests).isEmpty();
    }

    @Test
    void currencyMismatchIsRejected() {
        Refund refund = stack.appService().createRefund(
                new CreateRefundCommand("order-1", 1L, "user-1", 1000L, "USD", "customer",
                        "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(stack.payment.attemptRequests).isEmpty();
    }

    @Test
    void partialRefundReachesPartiallySucceededAndTracksConfirmedAmount() {
        // 渠道实际只退回 300（申请 1000）→ 部分成功，已确认金额 = 300。
        stack.payment.refundedAmountMinor = 300L;

        Refund refund = stack.appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PARTIALLY_SUCCEEDED);
        assertThat(refund.getRefundedAmountMinor()).isEqualTo(300L);
        assertThat(stack.entitlement.postProcessRequests).hasSize(1);
    }

    @Test
    void invalidRefundedAmountFallsToUnknown() {
        // 渠道回传金额非法（> 申请额）：禁止资金放大，落 UNKNOWN 待收敛。
        stack.payment.refundedAmountMinor = 2000L;

        Refund refund = stack.appService().createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(stack.entitlement.postProcessRequests).isEmpty();
    }

    @Test
    void cumulativeUsesConfirmedAmountForTerminalAndRequestedForInTransit() {
        // 第一笔部分退款 300（终态，计已确认额 300）。
        stack.payment.refundedAmountMinor = 300L;
        stack.appService().createRefund(
                new CreateRefundCommand("order-1", 1L, "user-1", 300L, "CNY", "customer",
                        "idem-1", List.of()));

        // 第二笔在途（渠道回 UNKNOWN，待权威结果收敛）：申请 400 计申请额。
        // 累计 = 300(已确认) + 400(在途申请额) = 700 <= 1000，应被批准（不落 REJECTED）。
        stack.payment.attemptStatus = "UNKNOWN";
        Refund second = stack.appService().createRefund(
                new CreateRefundCommand("order-1", 1L, "user-1", 400L, "CNY", "customer",
                        "idem-2", List.of()));
        assertThat(second.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(second.getStatus()).isNotEqualTo(RefundStatus.REJECTED);
    }
}
