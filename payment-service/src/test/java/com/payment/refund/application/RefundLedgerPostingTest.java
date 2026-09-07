package com.payment.refund.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 退款记账断言测试（005 T030 收口 / spec 019 T109 修复）：退款确认后 MUST 经
 * LedgerPostingGateway 记账，幂等键 = PMRF（{@code REFUND:<PMRF>} 前缀由出站网关
 * 统一添加——修 G5 双重前缀），sourceId 用业务单号 refundNo（ADR-0063），
 * 金额为申请全额（ADR-0016 部分退款已否决），重复退款幂等吸收不重复记账。
 */
class RefundLedgerPostingTest {

    private final RefundTestStack stack = new RefundTestStack();

    @Test
    void successfulRefundPostsLedgerWithPmrfKeyAndFullAmount() {
        Refund refund = stack.appService().createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "idem-1", List.of()));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.ledger.postingKeys).hasSize(1);

        // 记录格式：idempotencyKey + ":" + refundNo + ":" + amountMinor
        // spec 019：调用方只传 PMRF（"REFUND:" 前缀统一由 RefundFeignLedgerPostingGateway 添加）
        String posting = stack.ledger.postingKeys.get(0);
        String[] parts = posting.split(":");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo(refund.getRefundNo()); // 幂等键 = PMRF
        assertThat(parts[0]).startsWith("PMRF");              // 双层单号：支付层执行单
        assertThat(parts[1]).isEqualTo(refund.getRefundNo()); // sourceId 用业务单号
        assertThat(parts[2]).isEqualTo("1000");               // 申请全额
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
