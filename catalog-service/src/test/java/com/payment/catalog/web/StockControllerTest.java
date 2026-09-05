package com.payment.catalog.web;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.catalog.api.StockController;
import com.payment.catalog.application.StockApplicationService;
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
 * 库存内部端点单测（013 收口遗留，T-stock-controller）：验证 /internal/stock/*
 * 四个端点的接线与参数透传（standalone MockMvc，异常映射由 GlobalExceptionHandler 负责）。
 */
@ExtendWith(MockitoExtension.class)
class StockControllerTest {

    @Mock
    private StockApplicationService service;

    @InjectMocks
    private StockController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void seedWiresSkuAndTotal() throws Exception {
        mvc.perform(post("/internal/stock/seed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":103,\"total\":10}"))
                .andExpect(status().isOk());

        verify(service).seedStock(103L, 10L);
    }

    @Test
    void reserveWiresReservationIdSkuAndQuantity() throws Exception {
        mvc.perform(post("/internal/stock/reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"order:O-1:sku:103\",\"skuId\":103,\"quantity\":2}"))
                .andExpect(status().isOk());

        verify(service).reserve("order:O-1:sku:103", 103L, 2L);
    }

    @Test
    void confirmWiresDeductIdAsIdempotencyKey() throws Exception {
        mvc.perform(post("/internal/stock/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"order:O-1:sku:103\",\"skuId\":103,"
                                + "\"quantity\":2,\"deductId\":\"PM-1\"}"))
                .andExpect(status().isOk());

        verify(service).confirm("order:O-1:sku:103", 103L, 2L, "PM-1");
    }

    @Test
    void releaseWiresReservationIdSkuAndQuantity() throws Exception {
        mvc.perform(post("/internal/stock/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationId\":\"order:O-1:sku:103\",\"skuId\":103,\"quantity\":2}"))
                .andExpect(status().isOk());

        verify(service).release("order:O-1:sku:103", 103L, 2L);
    }
}
