package com.payment.settlement.api;

import com.payment.settlement.application.SettlementApplicationService;
import com.payment.settlement.domain.SettlementAdjustment;
import com.payment.settlement.domain.SettlementBatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    @GetMapping("/batches")
    public List<SettlementBatchResponse> listBatches(@RequestParam(required = false) String merchantId,
                                                     @RequestParam(required = false) String period) {
        return applicationService.listBatches(merchantId, period).stream()
                .map(SettlementBatchResponse::from)
                .toList();
    }

    @PostMapping("/batches/{id}/resolve")
    public SettlementBatchResponse resolveBatch(@PathVariable Long id,
                                                @RequestBody ResolveSettlementRequest request) {
        return SettlementBatchResponse.from(applicationService.resolveBatch(id, request.status()));
    }

    @PostMapping("/batches/{id}/close")
    public SettlementBatchResponse closeBatch(@PathVariable Long id,
                                              @RequestBody CloseBatchRequest request) {
        return SettlementBatchResponse.from(applicationService.closeBatch(id, request.operator()));
    }

    @PostMapping("/adjustments")
    public SettlementAdjustmentResponse registerAdjustment(@RequestBody RegisterAdjustmentRequest request) {
        SettlementAdjustment adjustment = applicationService.registerAdjustment(
                request.merchantId(), request.period(), request.idempotencyKey(),
                request.amountMinor(), request.direction(), request.currencyCode(),
                request.reason(), request.operator());
        return SettlementAdjustmentResponse.from(adjustment);
    }
}
