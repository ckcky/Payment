package com.payment.order.scenario;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.order.application.CatalogClient;
import com.payment.order.application.CreateOrderResult;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.SkuSnapshot;
import com.payment.order.domain.OrderStatus;
import com.payment.order.domain.Transaction;
import com.payment.order.infra.InMemoryOrderRepository;
import com.payment.order.infra.InMemoryTransactionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 成功购买端到端 RPC 场景（订单侧，T020）：SKU RPC 校验 + 价格快照 + Order 1:1 Transaction。
 *
 * <p>用内存 {@link CatalogClient} 作为 catalog-service 同步 RPC 的替身，验证订单服务侧的编排。</p>
 */
class SuccessfulPurchaseScenarioTest {

    private final InMemoryOrderRepository orderRepository = new InMemoryOrderRepository();
    private final InMemoryTransactionRepository transactionRepository = new InMemoryTransactionRepository();

    private OrderApplicationService service(CatalogClient client) {
        return new OrderApplicationService(orderRepository, transactionRepository, client);
    }

    private static CatalogClient catalog(SkuSnapshot... skus) {
        Map<Long, SkuSnapshot> byId = new HashMap<>();
        for (SkuSnapshot sku : skus) {
            byId.put(sku.skuId(), sku);
        }
        return skuId -> {
            SkuSnapshot sku = byId.get(skuId);
            if (sku == null) {
                throw BizException.of(ErrorCodes.NOT_FOUND, "sku not found: " + skuId);
            }
            return sku;
        };
    }

    @Test
    void createOrderSnapshotsPricesAndCreatesOneToOneTransaction() {
        CatalogClient client = catalog(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", true));
        OrderApplicationService service = service(client);

        CreateOrderResult result = service.createOrder("u1", "m1", List.of(new OrderLine(1L, 2)));

        assertThat(result.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.totalMinor()).isEqualTo(200L);
        assertThat(result.currencyCode()).isEqualTo("CNY");
        assertThat(result.orderId()).isNotNull();
        assertThat(result.transactionId()).isNotNull();

        Transaction transaction = transactionRepository.findByOrderId(String.valueOf(result.orderId()))
                .orElseThrow();
        assertThat(transaction.getAmountMinor()).isEqualTo(200L);
        assertThat(transaction.getCurrencyCode()).isEqualTo("CNY");
    }

    @Test
    void nonSellableSkuRejected() {
        CatalogClient client = catalog(new SkuSnapshot(1L, "SKU-A", "Item A", 100, "CNY", false));
        OrderApplicationService service = service(client);

        assertThatThrownBy(() -> service.createOrder("u1", "m1", List.of(new OrderLine(1L, 1))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void unknownSkuRejected() {
        CatalogClient client = catalog();
        OrderApplicationService service = service(client);

        assertThatThrownBy(() -> service.createOrder("u1", "m1", List.of(new OrderLine(999L, 1))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.NOT_FOUND));
    }
}
