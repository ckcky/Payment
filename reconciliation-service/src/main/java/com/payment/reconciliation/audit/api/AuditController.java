package com.payment.reconciliation.audit.api;

import com.payment.reconciliation.audit.application.AuditApplicationService;
import com.payment.reconciliation.audit.application.LedgerBalance;
import com.payment.reconciliation.audit.domain.AuditBatch;
import com.payment.reconciliation.audit.domain.AuditDifference;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计四核对 + 挂账调账 REST 接口（spec 017 / FR-021）。
 *
 * <p>与既有 {@code /internal/reconciliation/**} 平级，不动 006 契约（NFR-006）。
 * 契约见 plan §10.3。</p>
 */
@RestController
@RequestMapping("/internal/audit")
public class AuditController {

    private final AuditApplicationService applicationService;

    public AuditController(AuditApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public AuditBatchResponse createBatch(@Valid @RequestBody CreateAuditBatchRequest request) {
        AuditBatch batch = applicationService.runBatch(request.period(), request.scope(), request.triggeredBy());
        return toResponse(batch);
    }

    @GetMapping("/batches/{batchNo}")
    public AuditBatchResponse getBatch(@PathVariable String batchNo) {
        return toResponse(applicationService.getBatch(batchNo));
    }

    @GetMapping("/batches/{batchNo}/differences")
    public List<AuditDifferenceResponse> listDifferences(@PathVariable String batchNo) {
        return applicationService.listDifferences(batchNo).stream()
                .map(AuditDifferenceResponse::from)
                .toList();
    }

    @PostMapping("/batches/{batchNo}/differences/{differenceId}/suspend")
    public AuditAdjustmentResponse suspend(@PathVariable String batchNo, @PathVariable Long differenceId,
                                           @Valid @RequestBody SuspendDifferenceRequest request) {
        return AuditAdjustmentResponse.from(
                applicationService.suspend(batchNo, differenceId, request.operator(), request.reason()));
    }

    @PostMapping("/batches/{batchNo}/differences/{differenceId}/adjust")
    public AuditAdjustmentResponse adjust(@PathVariable String batchNo, @PathVariable Long differenceId,
                                          @Valid @RequestBody AdjustDifferenceRequest request) {
        return AuditAdjustmentResponse.from(applicationService.adjust(batchNo, differenceId,
                request.kind(), request.amountMinor(), request.targetAccountCode(),
                request.operator(), request.reviewer(), request.reason()));
    }

    @PostMapping("/batches/{batchNo}/recheck")
    public AuditBatchResponse recheck(@PathVariable String batchNo) {
        return toResponse(applicationService.recheck(batchNo));
    }

    @PostMapping("/batches/{batchNo}/close")
    public AuditBatchResponse close(@PathVariable String batchNo,
                                    @Valid @RequestBody CloseAuditBatchRequest request) {
        return toResponse(applicationService.close(batchNo, request.operator()));
    }

    @GetMapping("/batches/{batchNo}/adjustments")
    public List<AuditAdjustmentResponse> listAdjustments(@PathVariable String batchNo) {
        return applicationService.listAdjustments(batchNo).stream()
                .map(AuditAdjustmentResponse::from)
                .toList();
    }

    /** 结算门禁（plan §6.1 分级门禁）：settlement-service 建批前调用。 */
    @GetMapping("/settlement-gate")
    public AuditApplicationService.SettlementGateResponse settlementGate(@RequestParam String period) {
        return applicationService.settlementGate(period);
    }

    /** SUSPENSE 科目余额（SC-016）。 */
    @GetMapping("/suspense-balance")
    public SuspenseBalanceResponse suspenseBalance() {
        return new SuspenseBalanceResponse(applicationService.suspenseBalanceMinor(), "CNY");
    }

    /** 试算平衡（SC-018：任意处置序列后 Σ(借−贷)=0）。 */
    @GetMapping("/trial-balance")
    public LedgerBalance trialBalance() {
        return applicationService.trialBalance();
    }

    private AuditBatchResponse toResponse(AuditBatch batch) {
        List<AuditDifferenceResponse> differences = batch.getDifferences().stream()
                .map(AuditDifferenceResponse::from)
                .toList();
        return AuditBatchResponse.from(batch, differences);
    }
}
