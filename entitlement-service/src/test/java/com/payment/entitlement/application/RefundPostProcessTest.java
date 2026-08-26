package com.payment.entitlement.application;

import com.payment.common.dto.rpc.RefundPostProcessRequest;
import com.payment.common.dto.rpc.RefundPostProcessResponse;
import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementStatus;
import com.payment.entitlement.infra.InMemoryEntitlementRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款后处理：按订单撤销 AVAILABLE 权益，幂等返回 REVOKED / NOOP。
 */
class RefundPostProcessTest {

    private static Entitlement granted(String orderId) {
        Entitlement e = new Entitlement("user_1", orderId, "ful_" + orderId, 1, "default", null);
        e.grant();
        return e;
    }

    @Test
    void noEntitlementsForOrderReturnsNoop() {
        InMemoryEntitlementRepository repository = new InMemoryEntitlementRepository();
        EntitlementApplicationService service = new EntitlementApplicationService(repository);

        RefundPostProcessResponse response =
                service.revokeOnRefund(new RefundPostProcessRequest(1L, 2L, "order-1", "user_1", "refund"));

        assertThat(response.refundId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("NOOP");
    }

    @Test
    void revokesAvailableEntitlementForOrder() {
        InMemoryEntitlementRepository repository = new InMemoryEntitlementRepository();
        EntitlementApplicationService service = new EntitlementApplicationService(repository);

        Entitlement e = repository.save(granted("order-1"));

        RefundPostProcessResponse response =
                service.revokeOnRefund(new RefundPostProcessRequest(1L, 2L, "order-1", "user_1", "refund"));

        assertThat(response.status()).isEqualTo("REVOKED");
        assertThat(repository.findById(e.getId())).get()
                .extracting(Entitlement::getStatus)
                .isEqualTo(EntitlementStatus.REVOKED);
    }

    @Test
    void secondRefundIsIdempotentNoop() {
        InMemoryEntitlementRepository repository = new InMemoryEntitlementRepository();
        EntitlementApplicationService service = new EntitlementApplicationService(repository);

        repository.save(granted("order-1"));
        RefundPostProcessRequest request = new RefundPostProcessRequest(1L, 2L, "order-1", "user_1", "refund");

        assertThat(service.revokeOnRefund(request).status()).isEqualTo("REVOKED");
        assertThat(service.revokeOnRefund(request).status()).isEqualTo("NOOP");
    }
}
