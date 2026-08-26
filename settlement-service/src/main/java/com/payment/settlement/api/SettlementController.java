package com.payment.settlement.api;

import com.payment.settlement.application.SettlementApplicationService;
import com.payment.settlement.domain.SettlementBatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算领域对外 REST 接口（内部同步 RPC 面）。
 */
@RestController
@RequestMapping("/internal/settlements")
public class SettlementController {

    private final SettlementApplicationService applicationService;

    public SettlementController(SettlementApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/batches")
    public SettlementBatchResponse createBatch(@RequestBody CreateSettlementBatchRequest request) {
        SettlementBatch batch = applicationService.createBatch(
                request.merchantId(), request.period(), request.idempotencyKey());
        return SettlementBatchResponse.from(batch);
    }

    @GetMapping("/batches/{id}")
    public SettlementBatchResponse getBatch(@PathVariable Long id) {
        return SettlementBatchResponse.from(applicationService.getBatch(id));
    }

    @PostMapping("/batches/{id}/resolve")
    public SettlementBatchResponse resolveBatch(@PathVariable Long id,
                                                @RequestBody ResolveSettlementRequest request) {
        return SettlementBatchResponse.from(applicationService.resolveBatch(id, request.status()));
    }
}
