package com.payment.catalog.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.catalog.application.seckill.SeckillResult;
import com.payment.catalog.application.seckill.SeckillStockService;
import com.payment.common.core.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 秒杀端点单元测试（014）：验证 deduct 的 200/409 语义与 seed/rollback 的接线（standalone MockMvc）。
 */
@ExtendWith(MockitoExtension.class)
class SeckillStockControllerTest {

    @Mock
    private SeckillStockService service;

    @InjectMocks
    private SeckillStockController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void deductReturns200WithRemaining() throws Exception {
        when(service.tryPreDeduct(103L, 2L)).thenReturn(SeckillResult.allowed(8));

        mvc.perform(post("/internal/stock/seckill/deduct").param("skuId", "103").param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(8))
                .andExpect(jsonPath("$.bypassed").value(false));
    }

    @Test
    void deductInsufficientReturns409() throws Exception {
        when(service.tryPreDeduct(103L, 2L)).thenReturn(SeckillResult.deny());

        mvc.perform(post("/internal/stock/seckill/deduct").param("skuId", "103").param("quantity", "2"))
                .andExpect(status().isConflict());
    }

    @Test
    void seedAndRollbackAreOkAndWired() throws Exception {
        mvc.perform(post("/internal/stock/seckill/seed").param("skuId", "103").param("total", "10"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/stock/seckill/rollback").param("skuId", "103").param("quantity", "2"))
                .andExpect(status().isOk());

        verify(service).seed(103L, 10L);
        verify(service).rollback(103L, 2L);
    }
}
