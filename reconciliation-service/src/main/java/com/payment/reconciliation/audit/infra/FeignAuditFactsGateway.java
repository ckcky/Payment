package com.payment.reconciliation.audit.infra;

import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.reconciliation.application.ChannelStatementLoader;
import com.payment.reconciliation.audit.application.AuditFactsGateway;
import com.payment.reconciliation.audit.application.CertificateFact;
import com.payment.reconciliation.audit.application.LedgerBalance;
import com.payment.reconciliation.audit.application.LedgerPostingView;
import com.payment.reconciliation.audit.application.SettlementBatchFact;
import com.payment.reconciliation.domain.ChannelStatement;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AuditFactsGateway} 的 Feign 聚合实现：合并 payment / refund / settlement 三路事实
 * 与 ledger 只读视图、006 渠道账单加载器。任一数据源失败直接上抛（NFR-008）。
 */
@Component
public class FeignAuditFactsGateway implements AuditFactsGateway {

    private final com.payment.reconciliation.infra.client.PaymentFactsFeignClient paymentFactsClient;
    private final com.payment.reconciliation.infra.client.RefundFactsFeignClient refundFactsClient;
    private final SettlementAuditFeignClient settlementClient;
    private final LedgerAuditFeignClient ledgerClient;
    private final ChannelStatementLoader statementLoader;

    public FeignAuditFactsGateway(com.payment.reconciliation.infra.client.PaymentFactsFeignClient paymentFactsClient,
                                  com.payment.reconciliation.infra.client.RefundFactsFeignClient refundFactsClient,
                                  SettlementAuditFeignClient settlementClient,
                                  LedgerAuditFeignClient ledgerClient,
                                  ChannelStatementLoader statementLoader) {
        this.paymentFactsClient = paymentFactsClient;
        this.refundFactsClient = refundFactsClient;
        this.settlementClient = settlementClient;
        this.ledgerClient = ledgerClient;
        this.statementLoader = statementLoader;
    }

    @Override
    public List<CertificateFact> confirmedFacts(String period) {
        List<CertificateFact> facts = new ArrayList<>();
        List<com.payment.reconciliation.infra.client.PaymentFactDto> payments = paymentFactsClient.fetchConfirmedFacts();
        if (payments != null) {
            payments.forEach(d -> facts.add(new CertificateFact("PAYMENT", d.paymentNo(), d.channelReference(),
                    d.amountMinor(), d.currencyCode(), d.status())));
        }
        List<com.payment.reconciliation.infra.client.RefundFactDto> refunds = refundFactsClient.fetchConfirmedFacts();
        if (refunds != null) {
            refunds.forEach(d -> facts.add(new CertificateFact("REFUND", d.refundNo(), d.channelReference(),
                    d.amountMinor(), d.currencyCode(), d.status())));
        }
        for (SettlementBatchFact settlement : settlementFacts(period)) {
            // 结算批次事实按周期拉取后并入（账证核对覆盖 SETTLEMENT 来源，FR-001）
            facts.add(new CertificateFact("SETTLEMENT", String.valueOf(settlement.id()), settlement.batchNo(),
                    settlement.netMinor(), settlement.currency(), settlement.status()));
        }
        return List.copyOf(facts);
    }

    @Override
    public List<LedgerPostingView> ledgerPostings() {
        List<PostingResponse> postings = ledgerClient.allPostings();
        if (postings == null) {
            return List.of();
        }
        return postings.stream().map(p -> new LedgerPostingView(p.postingNo(), p.idempotencyKey(),
                p.sourceType(), p.sourceId(), p.currency(),
                p.entries() == null ? List.of() : p.entries().stream()
                        .map(e -> new LedgerPostingView.LedgerEntryView(e.accountId(), e.direction(),
                                e.amountMinor(), e.entryType(), p.sourceType(), p.sourceId()))
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
                .map(d -> new SettlementBatchFact(d.id(), d.batchNo(), d.status(), d.netMinor(), d.currencyCode()))
                .toList();
    }

    @Override
    public List<ChannelStatement> channelStatements(String period) {
        return statementLoader.load(period).statements();
    }
}
