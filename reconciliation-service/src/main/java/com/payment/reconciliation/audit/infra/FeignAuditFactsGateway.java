package com.payment.reconciliation.audit.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.reconciliation.audit.application.AuditFactsGateway;
import com.payment.reconciliation.audit.application.CertificateFact;
import com.payment.reconciliation.audit.application.LedgerBalance;
import com.payment.reconciliation.audit.application.LedgerPostingView;
import com.payment.reconciliation.audit.application.SettlementBatchFact;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AuditFactsGateway} 的 Feign 聚合实现：合并 payment / refund / settlement 三路事实
 * 与 ledger 只读视图、032 标准化账单（导入台账）。任一数据源失败直接上抛（NFR-008）。
 */
@Component
public class FeignAuditFactsGateway implements AuditFactsGateway {

    private final com.payment.reconciliation.infra.client.PaymentFactsFeignClient paymentFactsClient;
    private final com.payment.reconciliation.infra.client.RefundFactsFeignClient refundFactsClient;
    private final SettlementAuditFeignClient settlementClient;
    private final LedgerAuditFeignClient ledgerClient;
    private final StatementImportRepository statementImportRepository;

    public FeignAuditFactsGateway(com.payment.reconciliation.infra.client.PaymentFactsFeignClient paymentFactsClient,
                                  com.payment.reconciliation.infra.client.RefundFactsFeignClient refundFactsClient,
                                  SettlementAuditFeignClient settlementClient,
                                  LedgerAuditFeignClient ledgerClient,
                                  StatementImportRepository statementImportRepository) {
        this.paymentFactsClient = paymentFactsClient;
        this.refundFactsClient = refundFactsClient;
        this.settlementClient = settlementClient;
        this.ledgerClient = ledgerClient;
        this.statementImportRepository = statementImportRepository;
    }

    @Override
    public List<CertificateFact> confirmedFacts(String period) {
        List<CertificateFact> facts = new ArrayList<>();
        List<com.payment.reconciliation.infra.client.PaymentFactDto> payments = paymentFactsClient.fetchConfirmedFacts(period);
        if (payments != null) {
            payments.forEach(d -> facts.add(new CertificateFact("PAYMENT", d.paymentNo(), d.channelReference(),
                    d.amountMinor(), d.currencyCode(), d.status(), d.merchantId())));
        }
        List<com.payment.reconciliation.infra.client.RefundFactDto> refunds = refundFactsClient.fetchConfirmedFacts(period);
        if (refunds != null) {
            refunds.forEach(d -> facts.add(new CertificateFact("REFUND", d.refundNo(), d.channelReference(),
                    d.amountMinor(), d.currencyCode(), d.status(), d.merchantId())));
        }
        for (SettlementBatchFact settlement : settlementFacts(period)) {
            // 账证核对覆盖 SETTLEMENT 来源（FR-001）；031/M1：账本 sourceId 是 batchNo（弃数值 id）
            facts.add(new CertificateFact("SETTLEMENT", settlement.batchNo(), settlement.batchNo(),
                    settlement.netMinor(), settlement.currency(), settlement.status(), settlement.merchantId()));
        }
        return List.copyOf(facts);
    }

    @Override
    public List<LedgerPostingView> ledgerPostings() {
        List<AccountingEventResponse> postings = ledgerClient.allPostings();
        if (postings == null) {
            return List.of();
        }
        return postings.stream().map(p -> new LedgerPostingView(p.postingNo(), p.eventType(),
                p.idempotencyKey(), p.sourceType(), p.sourceId(), p.period(),
                p.postedAt() == null ? null : p.postedAt().toString(), p.status(),
                p.currency(), p.entries() == null ? List.of() : p.entries().stream()
                        .map(e -> new LedgerPostingView.LedgerEntryView(e.accountId(), e.accountCode(),
                                e.ownerType(), e.ownerId(), e.direction(), e.amountMinor()))
                        .toList())).toList();
    }

    @Override
    public LedgerBalance ledgerBalance() {
        LedgerAuditFeignClient.BalanceDto dto = ledgerClient.balance();
        return new LedgerBalance(dto.balanced(), dto.diffByCurrency() == null ? java.util.Map.of() : dto.diffByCurrency());
    }

    @Override
    public List<SettlementBatchFact> settlementFacts(String period) {
        List<SettlementAuditFeignClient.SettlementFactDto> dtos = settlementClient.auditFacts(period);
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(d -> new SettlementBatchFact(d.id(), d.batchNo(), d.status(), d.netMinor(), d.currencyCode(),
                        d.merchantId()))
                .toList();
    }

    @Override
    public StatementLoad channelStatementLoad(String period) {
        // 该周期最新 NORMALIZED 导入（任意渠道）；无导入 ⇒ 显式失败（NFR-008 / plan §2.10），
        // 绝不做「空账单 = 无差异」的静默核对。
        StatementImport imprt = statementImportRepository.findLatestNormalized(period, null)
                .orElseThrow(() -> BizException.of(ErrorCodes.STATEMENT_UNAVAILABLE,
                        "no normalized statement import for period " + period
                                + "; upload a statement before running REAL/ALL audit"));
        return new StatementLoad(statementImportRepository.findLines(imprt.getId()), imprt.getImportNo());
    }
}
