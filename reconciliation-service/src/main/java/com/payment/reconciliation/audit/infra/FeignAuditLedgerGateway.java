package com.payment.reconciliation.audit.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.common.dto.rpc.AccountingSourceType;
import com.payment.reconciliation.audit.application.AuditLedgerGateway;
import com.payment.reconciliation.audit.domain.AdjustmentPolicy;
import com.payment.reconciliation.posting.application.PostingEventTypes;
import com.payment.reconciliation.posting.application.PostingPendingRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link AuditLedgerGateway} 的 Feign 实现（031 事件化）：挂账 / 调账发
 * {@code ADJUSTMENT} 事件（source_type=RECONCILIATION，§5.3 来源域收敛）。
 * 记账失败不静默——处置 MUST 留痕，失败直接上抛（NFR-008 / plan §12 三重兜底）。
 */
@Component
public class FeignAuditLedgerGateway implements AuditLedgerGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignAuditLedgerGateway.class);

    private final LedgerAuditFeignClient ledgerClient;
    private final BusinessMetrics metrics;
    private final PostingPendingRecorder pendingRecorder;

    public FeignAuditLedgerGateway(LedgerAuditFeignClient ledgerClient, BusinessMetrics metrics,
                                   PostingPendingRecorder pendingRecorder) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
        this.pendingRecorder = pendingRecorder;
    }

    @Override
    public PostingResult postAdjustment(String adjustNo, String currency, AdjustmentPolicy.AdjustPlan plan) {
        AccountingEventRequest request = new AccountingEventRequest(
                AccountingEventType.ADJUSTMENT.name(), AccountingSourceType.RECONCILIATION.name(),
                adjustNo, currency,
                null, null, null, null, null, null,
                plan.adjustmentKind(), plan.fromAccountCode(), plan.toAccountCode(),
                plan.amountMinor(), plan.reversesEventType(), plan.reversesSourceId());
        try {
            AccountingEventResponse response = ledgerClient.postEvent(request);
            metrics.counter("audit.posting_succeeded", 1, "module", "reconciliation");
            log.info("audit adjustment posted: adjustNo={} kind={} postingNo={} postingId={}",
                    adjustNo, plan.adjustmentKind(), response.postingNo(), response.postingId());
            return new PostingResult(response.postingNo(), String.valueOf(response.postingId()));
        } catch (RuntimeException ex) {
            metrics.counter("audit.posting_failed", 1, "module", "reconciliation");
            // 出站失败台账（spec 034 §9.1）：上抛前先登记（NFR-008 失败上抛语义保留——
            // 登记动作绝不抛：即便台账写入失败，原异常仍原样上抛，处置留痕语义不变）
            pendingRecorder.recordFailure(PostingEventTypes.ADJUSTMENT,
                    AccountingSourceType.RECONCILIATION.name(), adjustNo,
                    AccountingEventType.ADJUSTMENT.name() + ":" + adjustNo,
                    request, ex.getMessage());
            log.error("audit adjustment posting failed: adjustNo={} reason={}", adjustNo, ex.getMessage());
            throw ex instanceof BizException biz ? biz
                    : BizException.of(ErrorCodes.INTERNAL_ERROR, "audit posting failed: " + ex.getMessage());
        }
    }
}
