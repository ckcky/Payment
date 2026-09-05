package com.payment.entitlement.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 权益应用服务：处理「履约完成」同步 RPC 并授予权益，以及「退款成功」后撤销权益。
 *
 * <p>以 {@code sourceFulfillmentId} 为幂等键：重复投递同一履约完成请求
 * 不会创建第二条权益。</p>
 */
@Service
public class EntitlementApplicationService {

    private static final String MODULE = "entitlement";

    private final EntitlementRepository repository;
    private final BusinessMetrics metrics;

    public EntitlementApplicationService(EntitlementRepository repository, BusinessMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    public Entitlement grantOnFulfillmentCompleted(FulfillmentCompletedRequest request) {
        // 幂等：同一 sourceFulfillmentId 只授予一次。
        Optional<Entitlement> existing =
                repository.findBySourceFulfillmentId(String.valueOf(request.fulfillmentId()));
        if (existing.isPresent()) {
            return existing.get();
        }
        Entitlement e = newEntitlement(request.userId(), request.orderNo(),
                String.valueOf(request.fulfillmentId()));
        try {
            e.grant();
        } catch (RuntimeException ex) {
            e.fail(ex.getMessage());
            metrics.counter("entitlement.grant.failed", 1.0, "module", MODULE);
            return repository.save(e);
        }
        metrics.counter("entitlement.granted", 1.0, "module", MODULE);
        return repository.save(e);
    }

    /** 测试缝隙：供单测注入可被拒绝的 mock 授予（不改动状态机）。 */
    Entitlement newEntitlement(String userId, String orderNo, String sourceFulfillmentId) {
        return new Entitlement(userId, orderNo, sourceFulfillmentId, 1, "default", null);
    }

    /**
     * 退款成功后的权益撤销：按订单撤销 AVAILABLE 权益，幂等返回 REVOKED / NOOP。
     *
     * <p>非 AVAILABLE 权益不在自动撤销范围（留待人工处理），不伪造撤销成功。
     * 无权益可撤时返回 {@code NOOP}；只要撤销了至少一条即返回 {@code REVOKED}。</p>
     */
    public RefundPostProcessResponse revokeOnRefund(RefundPostProcessRequest request) {
        List<Entitlement> list = repository.findByOrderNo(request.orderNo());
        if (list.isEmpty()) {
            return new RefundPostProcessResponse(request.refundNo(), "NOOP");
        }
        int revoked = 0;
        for (Entitlement e : list) {
            if (e.revokeForRefund()) {
                repository.save(e);
                revoked++;
            }
        }
        return new RefundPostProcessResponse(request.refundNo(), revoked > 0 ? "REVOKED" : "NOOP");
    }
}
