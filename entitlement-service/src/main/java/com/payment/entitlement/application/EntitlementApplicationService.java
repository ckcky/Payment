package com.payment.entitlement.application;

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

    private final EntitlementRepository repository;

    public EntitlementApplicationService(EntitlementRepository repository) {
        this.repository = repository;
    }

    public Entitlement grantOnFulfillmentCompleted(FulfillmentCompletedRequest request) {
        // 幂等：同一 sourceFulfillmentId 只授予一次。
        Optional<Entitlement> existing =
                repository.findBySourceFulfillmentId(String.valueOf(request.fulfillmentId()));
        if (existing.isPresent()) {
            return existing.get();
        }
        Entitlement e = new Entitlement(request.userId(), request.orderId(),
                String.valueOf(request.fulfillmentId()), 1, "default", null);
        e.grant();
        return repository.save(e);
    }

    /**
     * 退款成功后的权益撤销：按订单撤销 AVAILABLE 权益，幂等返回 REVOKED / NOOP。
     *
     * <p>非 AVAILABLE 权益不在自动撤销范围（留待人工处理），不伪造撤销成功。
     * 无权益可撤时返回 {@code NOOP}；只要撤销了至少一条即返回 {@code REVOKED}。</p>
     */
    public RefundPostProcessResponse revokeOnRefund(RefundPostProcessRequest request) {
        List<Entitlement> list = repository.findByOrderId(request.orderId());
        if (list.isEmpty()) {
            return new RefundPostProcessResponse(request.refundId(), "NOOP");
        }
        int revoked = 0;
        for (Entitlement e : list) {
            if (e.revokeForRefund()) {
                repository.save(e);
                revoked++;
            }
        }
        return new RefundPostProcessResponse(request.refundId(), revoked > 0 ? "REVOKED" : "NOOP");
    }
}
