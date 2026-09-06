package com.payment.fulfillment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import com.payment.fulfillment.domain.FulfillmentStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 履约应用服务：接收 payment-service 的同步 RPC，按订单明细（order_item）粒度创建幂等履约任务
 * （spec 018 / ADR-0066：每个 order_item 一条履约，orderItemId = OI 业务单号）；
 * 履约完成后通过同步 RPC（{@link EntitlementGateway}）逐条触发权益授予。
 *
 * <p>幂等粒度 = {@code (sourcePaymentNo, orderItemId)}（AC3.3）：重复通知不产生重复履约，
 * 部分明细已存在的场景逐条跳过、仅补建缺失明细。</p>
 */
@Service
public class FulfillmentApplicationService {

    private static final String MODULE = "fulfillment";

    private final FulfillmentRepository repository;
    private final EntitlementGateway entitlementGateway;
    private final BusinessMetrics metrics;

    public FulfillmentApplicationService(FulfillmentRepository repository,
                                         EntitlementGateway entitlementGateway,
                                         BusinessMetrics metrics) {
        this.repository = repository;
        this.entitlementGateway = entitlementGateway;
        this.metrics = metrics;
    }

    public List<Fulfillment> acceptPaymentSucceeded(PaymentSucceededRequest request) {
        String sourcePaymentNo = request.paymentNo();
        List<PaymentSucceededRequest.ItemLine> items = request.items();
        // spec 018 / FR-005：order 层负责以本库 order_items 富化明细；items 缺失属契约违规，快速失败。
        if (items == null || items.isEmpty()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "payment succeeded request without items (contract violation, spec 018): "
                            + request.paymentNo());
        }

        List<Fulfillment> result = new ArrayList<>(items.size());
        for (PaymentSucceededRequest.ItemLine item : items) {
            // 明细粒度幂等：同一 (sourcePaymentNo, orderItemId) 已存在直接跳过（重复通知/部分重复吸收）
            var existing = repository.findBySourcePaymentNoAndOrderItemId(sourcePaymentNo, item.orderItemNo());
            if (existing.isPresent()) {
                result.add(existing.get());
                continue;
            }

            Fulfillment fulfillment = newFulfillment(request.orderNo(), item.orderItemNo(), sourcePaymentNo);
            fulfillment.start();

            // 同步 mock 处理（PROCESSING → DELIVERED）。真实实现会在此处调用交付渠道；
            // 未知结果绝不臆断为成功——异常时记录失败，不触发权益、不回写支付事实。
            try {
                fulfillment.deliver();
            } catch (RuntimeException ex) {
                fulfillment.fail(ex.getMessage());
                metrics.counter("fulfillment.failed", 1.0, "module", MODULE);
                result.add(repository.save(fulfillment));
                continue;
            }

            metrics.counter("fulfillment.completed", 1.0, "module", MODULE);

            Fulfillment saved = repository.save(fulfillment);
            result.add(saved);

            // 每条履约完成后各自触发权益授予（同步 RPC）；权益失败不反写履约成功事实。
            entitlementGateway.notifyFulfillmentCompleted(
                    new FulfillmentCompletedRequest(saved.getId(), saved.getOrderNo(), request.userId()));
        }
        return result;
    }

    /** 测试缝隙：供单测注入可失败的 mock 交付（不改动状态机）。 */
    Fulfillment newFulfillment(String orderNo, String orderItemId, String sourcePaymentNo) {
        return new Fulfillment(orderNo, orderItemId, "mock delivery", sourcePaymentNo);
    }

    /**
     * 退款 → 履约撤销（ADR-0017）：仅「请求撤销」而非「保证撤销」，尊重履约自身状态机。
     *
     * <p>spec 018 / AC3.4：一单多明细 = 多条履约，遍历取消全部 PENDING；任一条取消成功返回
     * CANCELLED；全部不可撤销（PROCESSING/DELIVERED/已取消等）返回 SKIPPED（可解释、非错误）；
     * 找不到履约也返回 SKIPPED。已交付履约的回收不在本 Feature。</p>
     */
    public RefundFulfillmentResponse onRefund(RefundFulfillmentRequest request) {
        List<Fulfillment> fulfillments = repository.findByOrderNo(request.orderNo());
        if (fulfillments.isEmpty()) {
            return new RefundFulfillmentResponse(request.refundNo(), "SKIPPED");
        }
        boolean anyCancelled = false;
        for (Fulfillment fulfillment : fulfillments) {
            if (fulfillment.getStatus() == FulfillmentStatus.PENDING) {
                fulfillment.cancel();
                repository.save(fulfillment);
                metrics.counter("fulfillment.refund_cancelled", 1.0, "module", MODULE);
                anyCancelled = true;
            }
        }
        return new RefundFulfillmentResponse(request.refundNo(), anyCancelled ? "CANCELLED" : "SKIPPED");
    }
}
