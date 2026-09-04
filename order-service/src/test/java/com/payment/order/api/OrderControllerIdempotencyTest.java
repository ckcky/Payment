package com.payment.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.order.application.CreateOrderResult;
import com.payment.order.application.OrderApplicationService;
import com.payment.order.application.OrderLine;
import com.payment.order.application.idempotency.IdempotencyDecision;
import com.payment.order.application.idempotency.OrderEntryIdempotencyService;
import com.payment.order.domain.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 下单入口幂等校验（控制器层）。使用 standalone MockMvc，避免加载完整 Spring 上下文导致的
 * MyBatis Mapper / Redis 自动装配问题（本控制器仅依赖两个被 mock 的应用服务）。
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerIdempotencyTest {

    @Mock
    private OrderApplicationService orderApplicationService;

    @Mock
    private OrderEntryIdempotencyService idempotency;

    @InjectMocks
    private OrderController orderController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(orderController).build();
    }

    private static final String BODY =
            "{\"userId\":\"demo-user\",\"merchantId\":\"1\",\"items\":[{\"skuId\":1,\"quantity\":1}]}";

    private CreateOrderResult sampleResult() {
        return new CreateOrderResult("OR1001", "TX1001", OrderStatus.PAID, 9900L, "CNY", "PM30", "SUCCEEDED");
    }

    @Test
    void noIdempotencyKey_proceedsWith201() throws Exception {
        when(idempotency.check(null)).thenReturn(IdempotencyDecision.proceed());
        when(orderApplicationService.createOrder(any(), any(), any(List.class), any())).thenReturn(sampleResult());

        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("OR1001"));
    }

    @Test
    void conflict_returns409WithRetryAfter() throws Exception {
        when(idempotency.check("k-1")).thenReturn(IdempotencyDecision.conflict());

        mockMvc.perform(post("/orders").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void replay_returns200WithStoredJson() throws Exception {
        when(idempotency.check("k-2")).thenReturn(IdempotencyDecision.replay("{\"orderNo\":\"OR1001\",\"status\":\"PAID\"}"));

        mockMvc.perform(post("/orders").header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"orderNo\":\"OR1001\",\"status\":\"PAID\"}"));
    }

    @Test
    void proceed_createsAndReturns201() throws Exception {
        when(idempotency.check("k-3")).thenReturn(IdempotencyDecision.proceed());
        when(orderApplicationService.createOrder(any(), any(), any(List.class), any())).thenReturn(sampleResult());

        mockMvc.perform(post("/orders").header("Idempotency-Key", "k-3")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("OR1001"));
    }
}
