package com.payment.entitlement.application;

import com.payment.common.dto.rpc.FulfillmentCompletedRequest;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 权益应用服务：处理「履约完成」同步 RPC 并授予权益。
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
}
