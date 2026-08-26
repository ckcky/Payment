package com.payment.payment.api;

import com.payment.payment.api.dto.PaymentFactResponse;
import com.payment.payment.application.PaymentFactsService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账事实查询接口：向 reconciliation-service 暴露平台侧已确认的支付事实。
 */
@RestController
public class ReconciliationFactsController {

    private final PaymentFactsService factsService;

    public ReconciliationFactsController(PaymentFactsService factsService) {
        this.factsService = factsService;
    }

    @GetMapping("/internal/payments/confirmed-facts")
    public List<PaymentFactResponse> confirmedFacts() {
        return factsService.confirmedFacts();
    }
}
