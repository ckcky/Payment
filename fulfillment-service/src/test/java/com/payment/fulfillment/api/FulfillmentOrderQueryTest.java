package com.payment.fulfillment.api;

import com.payment.common.core.error.GlobalExceptionHandler;
import com.payment.fulfillment.domain.Fulfillment;
import com.payment.fulfillment.domain.FulfillmentStatus;
import com.payment.fulfillment.infra.InMemoryFulfillmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按订单查询履约的只读端点（spec 011 / FR-008）。
 *
 * <p>存在理由：支付成功触发履约后，调用方手上只有 {@code orderNo}，履约 id 是 payment-service
 * 内部 RPC 的返回值。缺了这个端点，校验「这笔订单的履约走到哪一步」只能直连数据库，
 * 那会绕过服务边界（宪章 IV.4）。</p>
 *
 * <p>用 standalone MockMvc + 内存仓储，不启 Spring 上下文、不连数据库：本测试只关心
 * 路由映射与 404 语义，与持久化无关。</p>
 */
class FulfillmentOrderQueryTest {

    private InMemoryFulfillmentRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFulfillmentRepository();
        mockMvc = MockMvcBuilders.standaloneSetup(new FulfillmentController(repository))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("命中：按订单号返回该订单的履约，含状态与来源支付")
    void returnsFulfillmentOfOrder() throws Exception {
        Fulfillment fulfillment = new Fulfillment("order-1", "item-1", "digital-key-1", "pay-1");
        fulfillment.start();
        fulfillment.deliver();
        repository.save(fulfillment);

        mockMvc.perform(get("/fulfillments/by-order/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("order-1"))
                .andExpect(jsonPath("$.sourcePaymentNo").value("pay-1"))
                .andExpect(jsonPath("$.status").value(FulfillmentStatus.DELIVERED.name()));
    }

    @Test
    @DisplayName("未命中：返回 404 且错误码 NOT_FOUND，不返回 null 或空体")
    void missingOrderReturns404() throws Exception {
        mockMvc.perform(get("/fulfillments/by-order/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does-not-exist")));
    }

    @Test
    @DisplayName("只按订单号匹配：不存在的订单号不会误命中已存在的履约")
    void doesNotLeakAcrossOrders() throws Exception {
        Fulfillment fulfillment = new Fulfillment("order-1", "item-1", "digital-key-1", "pay-1");
        repository.save(fulfillment);

        mockMvc.perform(get("/fulfillments/by-order/order-2"))
                .andExpect(status().isNotFound());
    }
}
