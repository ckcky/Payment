package com.payment.reconciliation.audit.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.reconciliation.audit.application.AuditLedgerGateway;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AuditLedgerGateway} 的 Feign 实现：挂账 / 调账经 ledger 标准记账通道落
 * {@code source_type=ADJUSTMENT} 分录（与 settlement 的 LedgerPostingGateway 同款通道）。
 * 记账失败不静默——处置 MUST 留痕，失败直接上抛（NFR-008 / plan §12 三重兜底）。
 */
@Component
public class FeignAuditLedgerGateway implements AuditLedgerGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignAuditLedgerGateway.class);

    private final LedgerAuditFeignClient ledgerClient;
    private final BusinessMetrics metrics;

    public FeignAuditLedgerGateway(LedgerAuditFeignClient ledgerClient, BusinessMetrics metrics) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
    }

    @Override
    public PostingResult postAdjustment(String idempotencyKey, String adjustNo, String currency,
                                        List<AdjustmentPolicy.PostingEntry> entries) {
        List<PostingRequest.EntryRequest> requestEntries = new ArrayList<>();
        for (AdjustmentPolicy.PostingEntry entry : entries) {
            requestEntries.add(new PostingRequest.EntryRequest(entry.accountId(), entry.direction(),
                    entry.amountMinor(), "ADJUSTMENT"));
        }
        PostingRequest request = new PostingRequest(idempotencyKey, "ADJUSTMENT", adjustNo,
                currency, requestEntries);
        try {
            PostingResponse response = ledgerClient.post(request);
            metrics.counter("audit.posting_succeeded", 1, "module", "reconciliation");
            log.info("audit adjustment posted: adjustNo={} postingNo={} postingId={}",
                    adjustNo, response.postingNo(), response.postingId());
            return new PostingResult(response.postingNo(), String.valueOf(response.postingId()));
        } catch (RuntimeException ex) {
            metrics.counter("audit.posting_failed", 1, "module", "reconciliation");
            log.error("audit adjustment posting failed: adjustNo={} reason={}", adjustNo, ex.getMessage());
            throw ex instanceof BizException biz ? biz
                    : BizException.of(ErrorCodes.INTERNAL_ERROR, "audit posting failed: " + ex.getMessage());
        }
    }
}
