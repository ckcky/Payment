package com.payment.settlement.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.PostingRequest;
import com.payment.common.dto.rpc.PostingResponse;
import com.payment.settlement.application.LedgerPostingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 结算 → ledger-service 记账出站网关实现（ADR-0023）：满足 Constitution §II.3「一切资金变动 MUST 经 ledger」。
 *
 * <p>分录：借 {@code MERCHANT_PAYABLE}(2) / 贷 {@code SETTLEMENT_PAYABLE}(4)，金额 = netMinor，借贷平衡。
 * 幂等键固定 {@code SETTLEMENT:<batchIdempotencyKey>}。记账失败**不回滚**批次状态（禁 2PC/XA）：
 * 仅记录 {@code ledger.posting_failed} 指标与告警，进入「待记账」清单，由重试/对账兜底（与支付侧 ADR-0009 同口径）。</p>
 */
public class FeignLedgerPostingGateway implements LedgerPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignLedgerPostingGateway.class);
    private static final String MODULE = "settlement";

    /** 账本预置科目 ID（与 ledger-service Account 枚举一致，见 deployment/schema/09-ledger-schema.sql）。 */
    private static final long MERCHANT_PAYABLE = 2L;
    private static final long SETTLEMENT_PAYABLE = 4L;

    private final LedgerFeignClient ledgerClient;
    private final BusinessMetrics metrics;

    public FeignLedgerPostingGateway(LedgerFeignClient ledgerClient, BusinessMetrics metrics) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
    }

    @Override
    public void postSettlement(String idempotencyKey, Long batchId, long netMinor, String currencyCode) {
        String postingKey = "SETTLEMENT:" + idempotencyKey;
        PostingRequest request = new PostingRequest(postingKey, "SETTLEMENT", String.valueOf(batchId),
                currencyCode, buildEntries(netMinor));
        try {
            PostingResponse response = ledgerClient.post(request);
            metrics.counter("ledger.posting_succeeded", 1.0, "module", MODULE);
            log.info("记账成功 settlement batchId={} postingId={}", batchId, response.postingId());
        } catch (RuntimeException ex) {
            // 记账失败不回滚结算成功事实；记录待记账，交由重试/对账兜底（ADR-0023）
            metrics.counter("ledger.posting_failed", 1.0, "module", MODULE);
            log.error("记账失败，进入待记账兜底：batchId={} postingKey={} reason={}",
                    batchId, postingKey, ex.getMessage());
        }
    }

    /** 结算成功分录：借应付商户 N / 贷结算应付 N（借贷平衡，N = netMinor）。 */
    private List<PostingRequest.EntryRequest> buildEntries(long netMinor) {
        List<PostingRequest.EntryRequest> entries = new ArrayList<>();
        entries.add(new PostingRequest.EntryRequest(MERCHANT_PAYABLE, "DEBIT", netMinor, "SETTLEMENT"));
        entries.add(new PostingRequest.EntryRequest(SETTLEMENT_PAYABLE, "CREDIT", netMinor, "SETTLEMENT"));
        return entries;
    }
}
