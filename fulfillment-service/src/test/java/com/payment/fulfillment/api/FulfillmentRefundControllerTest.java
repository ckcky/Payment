package com.payment.fulfillment.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.common.dto.rpc.RefundFulfillmentRequest;
import com.payment.common.dto.rpc.RefundFulfillmentResponse;
import com.payment.fulfillment.application.FulfillmentApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款 → 履约撤销端点单测（005 T017 收口）：POST /internal/fulfillments/on-refund
 * 的接线与响应映射（PENDING → CANCELLED，其余 → SKIPPED）。standalone MockMvc。
 */
@ExtendWith(MockitoExtension.class)
class FulfillmentRefundControllerTest {

    @Mock
    private FulfillmentApplicationService applicationService;

    @InjectMocks
    private FulfillmentRefundController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void onRefundReturnsDownstreamStatusAndPassesRequestThrough() throws Exception {
        when(applicationService.onRefund(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RefundFulfillmentResponse("R-1", "CANCELLED"));

        mvc.perform(post("/internal/fulfillments/on-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundNo\":\"R-1\",\"paymentNo\":\"PM-1\",\"orderNo\":\"O-1\","
                                + "\"userId\":\"u1\",\"reason\":\"duplicate success\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundNo").value("R-1"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        ArgumentCaptor<RefundFulfillmentRequest> captor =
                ArgumentCaptor.forClass(RefundFulfillmentRequest.class);
        verify(applicationService).onRefund(captor.capture());
        assertThat(captor.getValue().refundNo()).isEqualTo("R-1");
        assertThat(captor.getValue().orderNo()).isEqualTo("O-1");
        assertThat(captor.getValue().paymentNo()).isEqualTo("PM-1");
    }

    @Test
    void onRefundPropagatesSkippedStatusWhenFulfillmentNotPending() throws Exception {
        when(applicationService.onRefund(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RefundFulfillmentResponse("R-2", "SKIPPED"));

        mvc.perform(post("/internal/fulfillments/on-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundNo\":\"R-2\",\"paymentNo\":\"PM-1\",\"orderNo\":\"O-1\","
                                + "\"userId\":\"u1\",\"reason\":\"already fulfilled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));
    }
}
