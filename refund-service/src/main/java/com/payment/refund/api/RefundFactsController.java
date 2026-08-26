package com.payment.refund.api;

import com.payment.refund.api.dto.RefundFactResponse;
import com.payment.refund.application.RefundFactsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账事实查询接口：向 reconciliation-service 暴露平台侧已确认的退款事实。
 */
@RestController
public class RefundFactsController {

    private final RefundFactsService factsService;

    public RefundFactsController(RefundFactsService factsService) {
        this.factsService = factsService;
    }

    @GetMapping("/internal/refunds/confirmed-facts")
    public List<RefundFactResponse> confirmedFacts() {
        return factsService.confirmedFacts();
    }
}
