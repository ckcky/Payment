package com.payment.entitlement.api;

import com.payment.entitlement.domain.Entitlement;
import com.payment.entitlement.domain.EntitlementStatus;
import com.payment.entitlement.infra.InMemoryEntitlementRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按订单查询权益的只读端点（spec 011 / FR-008）。
 *
 * <p>与单条查询的差异是<b>基数语义</b>：一个订单可对应多条权益（多 SKU / 多次授予），
 * 故返回列表；订单尚无权益时返回<b>空列表而不是 404</b>——「还没授予」是合法中间态，不是错误。
 * 这两条语义是本测试真正要钉住的东西。</p>
 */
class EntitlementOrderQueryTest {

    private static final LocalDateTime EXPIRY = LocalDateTime.of(2030, 1, 1, 0, 0);

    private InMemoryEntitlementRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryEntitlementRepository();
        mockMvc = MockMvcBuilders.standaloneSetup(new EntitlementController(repository)).build();
    }

    @Test
    @DisplayName("命中：返回该订单的全部权益（列表），状态为 AVAILABLE")
    void returnsEntitlementsOfOrder() throws Exception {
        Entitlement first = new Entitlement("user-1", "order-1", "1", 1, "VIP_MONTH", EXPIRY);
        first.grant();
        repository.save(first);

        mockMvc.perform(get("/entitlements/by-order/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("order-1"))
                .andExpect(jsonPath("$[0].status").value(EntitlementStatus.AVAILABLE.name()))
                .andExpect(jsonPath("$[0].availableQuantity").value(1));
    }

    @Test
    @DisplayName("多权益：同一订单的多条权益全部返回，不截断")
    void returnsAllEntitlementsWhenOrderHasSeveral() throws Exception {
        for (int i = 1; i <= 2; i++) {
            Entitlement e = new Entitlement("user-1", "order-1", String.valueOf(i), 1, "SKU_" + i, EXPIRY);
            e.grant();
            repository.save(e);
        }

        mockMvc.perform(get("/entitlements/by-order/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("未授予：返回 200 空列表，而不是 404（「还没授予」是中间态而非错误）")
    void missingOrderReturnsEmptyListNot404() throws Exception {
        mockMvc.perform(get("/entitlements/by-order/does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("按订单隔离：不会返回其它订单的权益")
    void doesNotLeakAcrossOrders() throws Exception {
        Entitlement mine = new Entitlement("user-1", "order-1", "1", 1, "VIP_MONTH", EXPIRY);
        repository.save(mine);

        mockMvc.perform(get("/entitlements/by-order/order-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
    }
}
