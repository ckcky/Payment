package com.payment.fulfillment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentRepository;
import com.payment.fulfillment.domain.FulfillmentStatus;
import com.payment.fulfillment.mq.FulfillmentEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(FulfillmentApplicationService.class);
    private static final String MODULE = "fulfillment";

    private final FulfillmentRepository repository;
    private final EntitlementGateway entitlementGateway;
    private final BusinessMetrics metrics;
    /** spec 029 / FR-206/207 / T44、T46：`mq.enabled=true` 时存在；否则回落同步 Feign（FR-306）。 */
    private final FulfillmentEventPublisher mq;

    @Autowired
    public FulfillmentApplicationService(FulfillmentRepository repository,
                                         EntitlementGateway entitlementGateway,
                                         BusinessMetrics metrics,
                                         ObjectProvider<FulfillmentEventPublisher> mqProvider) {
        this.repository = repository;
        this.entitlementGateway = entitlementGateway;
        this.metrics = metrics;
        this.mq = mqProvider.getIfAvailable();
    }

    /** 便捷构造（测试用，MQ 关闭）：回落同步 Feign（FR-306）。 */
    public FulfillmentApplicationService(FulfillmentRepository repository,
                                         EntitlementGateway entitlementGateway,
                                         BusinessMetrics metrics) {
        this(repository, entitlementGateway, metrics, (FulfillmentEventPublisher) null);
    }

    /** 显式指定发布器（测试用；生产走 {@code ObjectProvider} 主构造）。 */
    public FulfillmentApplicationService(FulfillmentRepository repository,
                                         EntitlementGateway entitlementGateway,
                                         BusinessMetrics metrics,
                                         FulfillmentEventPublisher mq) {
        this.repository = repository;
        this.entitlementGateway = entitlementGateway;
        this.metrics = metrics;
        this.mq = mq;
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

            // 每条履约完成后各自触发权益授予；权益失败不反写履约成功事实。
            // spec 029 / T46、FR-206：fulfillment.completed 改事务消息（点对点 → entitlement），
            // 替代同步 EntitlementGateway.notifyFulfillmentCompleted。SC-1：同步通知点清零。
            FulfillmentCompletedRequest completed = new FulfillmentCompletedRequest(
                    saved.getId(), saved.getOrderNo(), request.userId());
            if (mq != null) {
                try {
                    mq.publishFulfillmentCompleted(completed);
                } catch (RuntimeException ex) {
                    // commit 失败不回滚履约成功事实（INV-1）；半消息由回查按 fulfillments 表补投
                    metrics.counter("fulfillment.entitlement_grant_failed", 1.0, "module", MODULE);
                    log.warn("MQ 发布 fulfillment.completed 失败（事实不回滚，回查补投）orderNo={} reason={}",
                            saved.getOrderNo(), ex.getMessage());
                }
            } else {
                try {
                    entitlementGateway.notifyFulfillmentCompleted(completed);
                } catch (RuntimeException ex) {
                    metrics.counter("fulfillment.entitlement_grant_failed", 1.0, "module", MODULE);
                    log.warn("entitlement grant failed (reconciliation fallback) orderNo={} reason={}",
                            saved.getOrderNo(), ex.getMessage());
                }
            }
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
     *
     * <p>spec 019 / ADR-0067：履约与权益是同一条授予链的两端——本方法在撤 PENDING 履约后，
     * 沿「fulfillment → entitlement」既定链触发权益撤销（幂等 REVOKED/NOOP）。
     * 权益撤销失败不反写履约取消事实（对账兜底），留 WARN + 指标。</p>
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
        // 权益撤销（幂等）：只要订单存在权益即触发（含 DELIVERED 履约已授予的权益）
        // spec 029 / T44、FR-207：fulfillment.revoked 改事务消息（点对点 → entitlement），
        // 替代同步 EntitlementGateway.revokeOnRefund。SC-1：同步通知点清零。
        RefundPostProcessRequest revoke = new RefundPostProcessRequest(
                request.refundNo(), request.paymentNo(), request.orderNo(),
                request.userId(), request.reason());
        try {
            if (mq != null) {
                mq.publishFulfillmentRevoked(revoke);
            } else {
                entitlementGateway.revokeOnRefund(revoke);
            }
        } catch (RuntimeException ex) {
            metrics.counter("fulfillment.entitlement_revoke_failed", 1.0, "module", MODULE);
            log.warn("entitlement revocation failed (reconciliation fallback) orderNo={} refundNo={} reason={}",
                    request.orderNo(), request.refundNo(), ex.getMessage());
        }
        return new RefundFulfillmentResponse(request.refundNo(), anyCancelled ? "CANCELLED" : "SKIPPED");
    }
}
