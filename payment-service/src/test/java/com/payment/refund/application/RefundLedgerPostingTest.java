package com.payment.refund.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 退款记账断言测试（005 T030 收口）：退款确认后 MUST 经 LedgerPostingGateway 记账，
 * 幂等键格式为 {@code REFUND:<idempotencyKey>}（ADR-0063：sourceId 用业务单号 refundNo），
 * 金额为申请全额（ADR-0016 部分退款已否决），重复退款幂等吸收不重复记账。
 */
class RefundLedgerPostingTest {

    private final RefundTestStack stack = new RefundTestStack();

    @Test
    void successfulRefundPostsLedgerWithIdempotencyKeyAndFullAmount() {
        Refund refund = stack.appService().createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.ledger.postingKeys).hasSize(1);

        // 记录格式：idempotencyKey + ":" + refundNo + ":" + amountMinor
        String posting = stack.ledger.postingKeys.get(0);
        assertThat(posting).startsWith("REFUND:idem-1:");
        String[] parts = posting.split(":");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("REFUND");
        assertThat(parts[1]).isEqualTo("idem-1");
        assertThat(parts[2]).isEqualTo(refund.getRefundNo()); // sourceId 用业务单号
        assertThat(parts[3]).isEqualTo("1000");               // 申请全额
    }

    @Test
    void duplicateIdempotencyKeyDoesNotPostLedgerTwice() {
        RefundApplicationService service = stack.appService();
        CreateRefundCommand cmd = new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "idem-1", List.of());

        Refund first = service.createRefund(cmd);
        Refund second = service.createRefund(cmd);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stack.ledger.postingKeys).hasSize(1);
    }

    @Test
    void rejectedRefundDoesNotPostLedger() {
        // 超退款 → REJECTED，不触发任何后处理（含记账）
        stack.appService().createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1200L, "CNY", "customer", "idem-1", List.of()));

        assertThat(stack.ledger.postingKeys).isEmpty();
    }
}
