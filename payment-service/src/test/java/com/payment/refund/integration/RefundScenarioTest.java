package com.payment.refund.integration;

import com.payment.refund.application.CreateRefundCommand;
import com.payment.refund.application.RefundApplicationService;
import com.payment.refund.application.RefundRpcCallbackService;
import com.payment.refund.domain.Refund;
import com.payment.refund.domain.RefundPostProcessAttempt;
import com.payment.refund.domain.RefundStatus;
import com.payment.refund.support.RefundTestStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款 RPC 场景集成测试（T050/T058）：成功退款、重复退款、退款 UNKNOWN 收敛和退款后处理。
 *
 * <p>用 {@link RefundTestStack} 的内存仓储 + 记录式 payment/entitlement RPC fake
 * 作为跨服务同步 RPC 的替身，验证 refund-service 侧的编排与收敛。</p>
 */
class RefundScenarioTest {

    private final RefundTestStack stack = new RefundTestStack();
    private final RefundRpcCallbackService callback = new RefundRpcCallbackService(stack.refunds);

    private CreateRefundCommand cmd() {
        return new CreateRefundCommand("order-1", "PM-1", "user-1", 1000L, "CNY", "customer",
                "idem-1", List.of());
    }

    @Test
    void successfulRefundFiresAttemptAndPostProcessExactlyOnce() {
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.payment.attemptRequests).hasSize(1);
        assertThat(stack.entitlement.postProcessRequests).hasSize(1);
    }

    @Test
    void duplicateRefundDoesNotTriggerSecondFundAction() {
        RefundApplicationService service = stack.appService();

        Refund first = service.createRefund(cmd());
        Refund second = service.createRefund(cmd());

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(stack.payment.attemptRequests).hasSize(1);
        assertThat(stack.entitlement.postProcessRequests).hasSize(1);
    }

    @Test
    void unknownRefundConvergesToSuccessAndPostProcessIsIdempotent() {
        stack.payment.attemptStatus = "UNKNOWN";
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(cmd());
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(stack.entitlement.postProcessRequests).isEmpty(); // UNKNOWN 不触发后处理

        Refund resolved = callback.resolveRefund(refund.getRefundNo(), "SUCCEEDED");
        assertThat(resolved.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);

        // 重复收敛为幂等，状态不再变化
        Refund again = callback.resolveRefund(refund.getRefundNo(), "SUCCEEDED");
        assertThat(again.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    }

    @Test
    void failedRefundDoesNotTriggerPostProcess() {
        stack.payment.attemptStatus = "FAILED";
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(stack.entitlement.postProcessRequests).isEmpty();
    }

    @Test
    void postProcessFailureDoesNotRollBackRefundSuccess() {
        stack.entitlement.failPostProcess = true;
        RefundApplicationService service = stack.appService();

        Refund refund = service.createRefund(cmd());

        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(stack.payment.attemptRequests).hasSize(1);
        // 后处理虽失败但未回滚退款成功（ADR-0017 Saga）；ENTITLEMENT 尝试被记录为 FAILED 供重放。
        // 编排器对失败目标同步重试 3 次（ADR-0017），故 RPC 调用计 3 次，逻辑尝试记录仅 1 条。
        assertThat(stack.entitlement.postProcessRequests).hasSize(3);
        assertThat(stack.attempts.findByRefundNo(refund.getRefundNo()).stream()
                .anyMatch(a -> "ENTITLEMENT".equals(a.getTarget()) && "FAILED".equals(a.getStatus())))
                .isTrue();
    }
}
