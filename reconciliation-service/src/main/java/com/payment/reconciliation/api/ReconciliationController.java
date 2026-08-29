package com.payment.reconciliation.api;

import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.domain.ReconciliationBatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对账服务对外 REST 接口（内部同步 RPC 面）。
 */
@RestController
@RequestMapping("/internal/reconciliation")
public class ReconciliationController {

    private final ReconciliationApplicationService applicationService;

    public ReconciliationController(ReconciliationApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/batches")
    public ReconciliationBatchResponse runReconciliation(@RequestBody RunReconciliationRequest request) {
        ReconciliationBatch batch = applicationService.runReconciliation(request.period());
        return ReconciliationBatchResponse.from(batch);
    }

    @GetMapping("/batches/{id}")
    public ReconciliationBatchResponse getBatch(@PathVariable Long id) {
        return ReconciliationBatchResponse.from(applicationService.getBatch(id));
    }

    @GetMapping("/batches/{id}/differences")
    public List<DifferenceResponse> listDifferences(@PathVariable Long id) {
        return applicationService.listDifferences(id).stream()
                .map(DifferenceResponse::from)
                .toList();
    }

    @PostMapping("/batches/{id}/differences/resolve")
    public DifferenceResponse resolveDifference(@PathVariable Long id,
                                                @RequestBody ResolveDifferenceRequest request) {
        return DifferenceResponse.from(applicationService.resolveDifference(
                id, request.reference(), request.resolutionNote(), request.resolvedBy(), request.resolvedAt()));
    }

    @PostMapping("/batches/{id}/close")
    public ReconciliationBatchResponse closeBatch(@PathVariable Long id,
                                                  @RequestBody CloseBatchRequest request) {
        return ReconciliationBatchResponse.from(applicationService.closeBatch(id, request.operator()));
    }

    @GetMapping("/settlement-summary")
    public ReconciliationSettlementSummaryResponse settlementSummary(@RequestParam String period) {
        return applicationService.settlementSummary(period);
    }
}
