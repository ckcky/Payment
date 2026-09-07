package com.payment.refund.integration;

import com.payment.payment.application.channel.ChannelResult;
import com.payment.refund.application.CreateRefundCommand;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundRpcCallbackService;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款 RPC 场景集成测试（T050/T058/spec 019 T110）：成功退款、重复退款、UNKNOWN 收敛、
 * 三路收敛一致性（同步 / 渠道回调 / resolve）与 order 通知（TXRF+PMRF 双号）。
 *
 * <p>用 {@link RefundTestStack} 的内存仓储 + 记录式 payment/ledger/order RPC fake 验证
 * refund 域编排与收敛。spec 019：原 fulfillment/entitlement 扇出移交 order 侧收口。</p>
 */
class RefundScenarioTest {

    private final RefundTestStack stack = new RefundTestStack();
    private final RefundRpcCallbackService callback =
            new RefundRpcCallbackService(stack.refunds, stack.resultProcessor());

    private CreateRefundCommand cmd() {
        return new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
    }

    @Test
    void successfulRefundFiresAttemptLedgerAndOrderNotify() {
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.payment.attemptRequests).hasSize(1);
        assertThat(stack.ledger.postingKeys).hasSize(1);
        // 存量路径（无 TXRF）不通知 order
        assertThat(stack.order.refundNotifications).isEmpty();
    }

    @Test
    void duplicateRefundDoesNotTriggerSecondFundAction() {
        RefundApplicationService service = stack.appService();

        Refund first = service.createRefund(cmd());
        Refund second = service.createRefund(cmd());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stack.payment.attemptRequests).hasSize(1);
        assertThat(stack.ledger.postingKeys).hasSize(1);
    }

    @Test
    void unknownRefundConvergesToSuccessViaResolveAndIsIdempotent() {
        stack.payment.attemptStatus = "UNKNOWN";
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(cmd());
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(stack.ledger.postingKeys).isEmpty(); // UNKNOWN 不记账

        Refund resolved = callback.resolveRefund(refund.getRefundNo(), "SUCCEEDED");
        assertThat(resolved.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.ledger.postingKeys).hasSize(1);

        // 重复收敛为幂等，状态不再变化、不重复记账
        Refund again = callback.resolveRefund(refund.getRefundNo(), "SUCCEEDED");
        assertThat(again.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.ledger.postingKeys).hasSize(1);
    }

    @Test
    void channelCallbackConvergesUnknownToTerminal() {
        stack.payment.attemptStatus = "UNKNOWN";
        Refund refund = stack.appService().createRefund(
                new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                        "idem-1", List.of(), "TX-1", "TXRF-1"));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);

        // 渠道异步回调推成功 → 终态 + 记账 + 通知 order（双号）
        Refund converged = callback.handleChannelCallback(refund.getRefundNo(),
                ChannelResult.success("mock-refund-ref"));
        assertThat(converged.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.ledger.postingKeys).hasSize(1);
        assertThat(stack.order.refundNotifications).hasSize(1);
        assertThat(stack.order.refundNotifications.get(0).transactionRefundNo()).isEqualTo("TXRF-1");
        assertThat(stack.order.refundNotifications.get(0).paymentRefundNo()).isEqualTo(refund.getRefundNo());

        // 回调重放：终态吸收，不重复记账/通知
        callback.handleChannelCallback(refund.getRefundNo(), ChannelResult.success("mock-refund-ref"));
        assertThat(stack.ledger.postingKeys).hasSize(1);
        assertThat(stack.order.refundNotifications).hasSize(1);
    }

    @Test
    void orderNotifyFailureDoesNotRollBackRefundSuccess() {
        stack.order.failRefundNotify = true;
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "TXRF-N1", List.of(),
                "TX-1", "TXRF-N1"));

        // 通知失败不回滚退款成功事实（Saga）；记账照常完成（对账兜底重放通知）
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.ledger.postingKeys).hasSize(1);
        assertThat(stack.order.refundNotifications).isEmpty();
    }

    @Test
    void failedRefundNotifiesOrderWithFailure() {
        stack.payment.attemptStatus = "FAILED";
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(new CreateRefundCommand(
                "order-1", "PM-1", "user-1", 1000L, "CNY", "customer", "TXRF-F1", List.of(),
                "TX-1", "TXRF-F1"));

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(stack.ledger.postingKeys).isEmpty(); // 失败不记账
        assertThat(stack.order.refundNotifications).hasSize(1);
        assertThat(stack.order.refundNotifications.get(0).status()).isEqualTo("FAILED");
    }
}
